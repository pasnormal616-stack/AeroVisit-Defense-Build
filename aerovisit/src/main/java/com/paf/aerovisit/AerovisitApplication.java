package com.paf.aerovisit;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AerovisitApplication {

    static final String MANILA = "Asia/Manila";

    // 🕒 Pins the JVM clock to Philippine time, so LocalDate.now(), the 7 AM / 5 PM / 2 AM schedules and the gate's
    // time windows stay in Manila time even on a cloud server that runs in UTC
    @PostConstruct
    public void pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(MANILA));
    }

    public static void main(String[] args) {
        SpringApplication.run(AerovisitApplication.class, args);
    }

}