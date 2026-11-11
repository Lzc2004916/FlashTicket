package com.example.flashticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.flashticket.mapper")
public class FlashTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashTicketApplication.class, args);
    }
}
