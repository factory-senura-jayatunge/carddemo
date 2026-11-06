package org.carddemo.billpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Bill payment (COBIL00C): balance preview and full-balance payment. */
@SpringBootApplication
public class BillPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillPayApplication.class, args);
    }
}
