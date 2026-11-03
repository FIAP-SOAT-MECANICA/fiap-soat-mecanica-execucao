package br.com.fiap.soat.mecanica;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExecucaoApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExecucaoApplication.class, args);
    }
}
