# Flink 2.1 + Kafka·Iceberg 커넥터. SQL 클라이언트로만 쓴다(PyFlink 없음, 이미지가 가볍다).
FROM flink:2.1.0-scala_2.12-java17
ARG MAVEN=https://repo1.maven.org/maven2
# Kafka 커넥터 4.0.1-2.0은 Flink 2.x용. Iceberg Flink 런타임 1.10은 Flink 2.0용이고 2.1에서도 쓴다.
# Hadoop 클라이언트는 Iceberg 카탈로그가 Configuration 클래스를 찾기 때문에 넣는다.
RUN cd /opt/flink/lib \
 && curl -fsSLO --retry 5 --retry-all-errors $MAVEN/org/apache/flink/flink-sql-connector-kafka/4.0.1-2.0/flink-sql-connector-kafka-4.0.1-2.0.jar \
 && curl -fsSLO --retry 5 --retry-all-errors $MAVEN/org/apache/iceberg/iceberg-flink-runtime-2.0/1.10.0/iceberg-flink-runtime-2.0-1.10.0.jar \
 && curl -fsSLO --retry 5 --retry-all-errors $MAVEN/org/apache/iceberg/iceberg-aws-bundle/1.10.0/iceberg-aws-bundle-1.10.0.jar \
 && curl -fsSLO --retry 5 --retry-all-errors $MAVEN/org/apache/hadoop/hadoop-client-api/3.3.6/hadoop-client-api-3.3.6.jar \
 && curl -fsSLO --retry 5 --retry-all-errors $MAVEN/org/apache/hadoop/hadoop-client-runtime/3.3.6/hadoop-client-runtime-3.3.6.jar
