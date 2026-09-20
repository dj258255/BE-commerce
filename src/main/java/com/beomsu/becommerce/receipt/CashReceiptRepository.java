package com.beomsu.becommerce.receipt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface CashReceiptRepository extends JpaRepository<CashReceipt, Long> {
    List<CashReceipt> findByOrderNo(String orderNo);
}
