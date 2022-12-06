package science.atlarge.wta.simulator.output

import science.atlarge.wta.simulator.core.SimulationObserver
import science.atlarge.wta.simulator.events.*
import science.atlarge.wta.simulator.model.Environment
import java.io.File
import java.nio.file.Path



class EnvironmentStatsCollector(
        private val env: Environment,
        private val outputPath: Path?
) : SimulationObserver() {

    private val outputFile: File

    init {
        //create target file and add the first row
        outputFile = outputPath!!.resolve("environment.csv").toFile()
        outputFile.appendText("timestamp,host_id,it_power_total\n")

        // register handler in this class
        registerEventHandler(EventType.EXPOSE_ENVIRONMENT, this::exposeEnv)
    }

    // handle EXPOSE_ENVIRONMENT event
    private fun exposeEnv(event: ExposeEnvironmentEvent) {
        outputFile.appendText(event.toOutFile())
    }

    override fun idString(): String {
        return "EnvironmentStatsCollector"
    }
}