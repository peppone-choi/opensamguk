package com.opensam

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class OpensamApplication

fun main(args: Array<String>) {
    runApplication<OpensamApplication>(*args)
}
