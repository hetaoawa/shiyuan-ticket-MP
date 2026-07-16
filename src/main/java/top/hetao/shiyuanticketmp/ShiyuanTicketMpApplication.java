package top.hetao.shiyuanticketmp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShiyuanTicketMpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShiyuanTicketMpApplication.class, args);
    }

}
