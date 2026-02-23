package com.opensam.bootstrap

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

@Component
class BootstrapExitRunner(
    private val applicationContext: ConfigurableApplicationContext,
    @Value("\${app.bootstrap.exit-on-ready:false}")
    private val exitOnReady: Boolean,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(BootstrapExitRunner::class.java)

    override fun run(args: ApplicationArguments) {
        if (!exitOnReady) {
            return
        }

        log.info("Bootstrap exit mode enabled, shutting down after startup initialization")
        val code = SpringApplication.exit(applicationContext, ExitCodeGenerator { 0 })
        exitProcess(code)
    }
}
