package com.hanstep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HanstepBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(HanstepBackendApplication.class, args);
    }

}
