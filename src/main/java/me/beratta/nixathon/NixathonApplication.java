package me.beratta.nixathon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NixathonApplication {

    private static final Logger log = LoggerFactory.getLogger(NixathonApplication.class);

    public static void main(String[] args) {
        log.info("Starting nixathon application");
        SpringApplication.run(NixathonApplication.class, args);
    }

}
