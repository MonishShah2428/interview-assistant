package com.interviewprep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InterviewAssistantApplication {

  public static void main(String[] args) {
    SpringApplication.run(InterviewAssistantApplication.class, args);
  }
}
