package com.beomsu.becommerce.personalization.internal;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.xerial.snappy.Snappy;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 캐시 값을 <b>어떻게 저장하는가</b> — E6(M6)의 독립변수.
 *
 * <p>캐시는 DB 조회를 없애도 <b>네트워크 비용은 없애지 않는다</b>. 값이 커지면 Redis 왕복이
 * tail 에서 튀고, 메모리도 그만큼 쓴다. 압축은 그 둘을 줄이는 대신 <b>CPU 를 쓰고</b>, 값이
 * 작으면 오히려 손해다(헤더·CPU 가 이득보다 크다). 그래서 정할 것은 하나다:
 * <b>몇 바이트를 넘을 때부터 압축할 것인가</b>.
 *
 * <p><b>표식(marker)을 붙인다.</b> 저장된 값이 압축됐는지 알아야 읽을 수 있는데, 압축 여부는
 * 길이로 알 수 없다(작은 값은 압축해도 커진다). 그래서 접두어로 표시하고 <b>그 바이트도 비용에
 * 포함한다</b> — 실제로 저장되는 양이 곧 청구서다.
 *
 * <p><b>왜 base64 인가</b>: 이 저장소의 Redis 관례가 {@code StringRedisTemplate} + 수동 인코딩이라
 * (ContextStore 참고) 값이 UTF-8 문자열이어야 한다. 압축 결과는 임의 바이트라 그대로 넣으면
 * 읽을 때 깨진다. 그래서 base64 로 감싼다 — <b>대가는 33% 팽창</b>이고, 이것도 이 실험이
 * 재는 비용이다(바이너리 저장이 가능한 직렬화로 바꾸면 사라지는 비용이라 리포트에 적는다).
 */
public enum CacheValueCodec {

    /** 압축하지 않는다. 값이 작을 때의 기준선이고, 이겼을 때만 압축을 켠다. */
    NONE("") {
        @Override
        byte[] compress(byte[] raw) {
            return raw;
        }

        @Override
        byte[] decompress(byte[] stored) {
            return stored;
        }
    },

    /** LZ4 — 빠르고 압축률은 낮다. 왕복이 잦은 캐시에 맞는 쪽이다. */
    LZ4("lz4:") {
        @Override
        byte[] compress(byte[] raw) {
            int max = COMPRESSOR.maxCompressedLength(raw.length);
            byte[] out = new byte[max];
            int size = COMPRESSOR.compress(raw, 0, raw.length, out, 0, max);
            // 압축 길이를 함께 실어야 복원할 수 있다(LZ4 블록은 원본 길이를 모른다).
            byte[] framed = new byte[size + 4];
            writeInt(framed, 0, raw.length);
            System.arraycopy(out, 0, framed, 4, size);
            return framed;
        }

        @Override
        byte[] decompress(byte[] stored) {
            int originalLength = readInt(stored, 0);
            byte[] out = new byte[originalLength];
            DECOMPRESSOR.decompress(stored, 4, out, 0, originalLength);
            return out;
        }
    },

    /** Snappy — LZ4 보다 압축률이 조금 낫고 조금 느리다. 값이 클 때 유리할 수 있다. */
    SNAPPY("snz:") {
        @Override
        byte[] compress(byte[] raw) {
            try {
                return Snappy.compress(raw);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        byte[] decompress(byte[] stored) {
            try {
                return Snappy.uncompress(stored);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    };

    private static final LZ4Factory FACTORY = LZ4Factory.fastestInstance();
    private static final LZ4Compressor COMPRESSOR = FACTORY.fastCompressor();
    private static final LZ4FastDecompressor DECOMPRESSOR = FACTORY.fastDecompressor();

    /** 압축 값임을 알리는 접두어. 이 바이트도 저장량에 포함된다. */
    private final String marker;

    CacheValueCodec(String marker) {
        this.marker = marker;
    }

    /** 사람이 읽는 저장 형태의 이름 — 리포트가 쓴다. */
    public String marker() {
        return marker;
    }

    abstract byte[] compress(byte[] raw);

    abstract byte[] decompress(byte[] stored);

    /** 이 코덱이 붙이는 표식의 길이(바이트). 압축 이득을 계산할 때 상수항이다. */
    public int markerBytes() {
        return marker.length();
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static int readInt(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 24) | ((source[offset + 1] & 0xFF) << 16)
                | ((source[offset + 2] & 0xFF) << 8) | (source[offset + 3] & 0xFF);
    }
}
