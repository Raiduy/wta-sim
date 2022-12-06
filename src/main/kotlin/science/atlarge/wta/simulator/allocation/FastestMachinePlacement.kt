package science.atlarge.wta.simulator.allocation

import science.atlarge.wta.simulator.model.Task
import science.atlarge.wta.simulator.model.Ticks
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class FastestMachinePlacement : TaskPlacementPolicy {

    override fun scheduleTasks(eligibleTasks: Iterator<Task>, callbacks: AllocationCallbacks, currentTime: Ticks) {
        // Compute the total amount of available resources to exit early
        var totalFreeCpu = callbacks.getNumberOfAvailableResources()
        val machineStates = callbacks.getMachineStatesByDescendingMachineSpeed()
        if(!machineStates.hasNext()) return
        var currentMachine = machineStates.next()

        // Loop through eligible tasks and try to place them on machines
        while (totalFreeCpu > 0 && eligibleTasks.hasNext()) {
            val task = eligibleTasks.next()
            var coresLeft = task.cpuDemand

            // Get a list of machines that can fit this task, in ascending order of energy efficiency
            while (coresLeft in 1..totalFreeCpu) {

                // Compute the runtime on this machine (in case we do not get assigned the fastest)
                val runTimeOnThisMachine = task.originalRuntime / currentMachine.normalizedSpeed
                val resourcesToUse = min(currentMachine.freeCpus, coresLeft)
                val energyConsumptionOnThisMachine = currentMachine.TDP.toDouble() /
                        currentMachine.machine.numberOfCpus *
                        resourcesToUse *
                        (runTimeOnThisMachine / 1000 / 3600)  // ms to seconds to hours to get Wh

                
                var cpusLeft = totalFreeCpu - resourcesToUse
                // println(" - - - - - - CPUsLeft is ${cpusLeft} - - - - - - ")
                // println(" - - - - - - ResourcesToUse is ${resourcesToUse} - - - - - - ")

                var idleConsumption = 900 / currentMachine.machine.numberOfCpus * cpusLeft * 900
                
                if (task.originalRuntime > 900000) {
                    println(" - - - - - - totalFreeCpu is ${totalFreeCpu} - - - - - - ")
                    println(" - - - - - - resourcesToUse is ${resourcesToUse} - - - - - - \n")
                }

                val energyConsumptionOnThisMachineJoules = (currentMachine.TDP.toDouble() /
                        currentMachine.machine.numberOfCpus *
                        resourcesToUse *
                        (runTimeOnThisMachine / 1000) ) + idleConsumption // conversion to Joules
                        // currentMachine.idleEnergyConsumption(cpusLeft)

                // Update machine metrics
                // if (energyConsumptionOnThisMachineJoules > 0) {
                    currentMachine.machine.instantEnergyConsumption = energyConsumptionOnThisMachineJoules
                // } else {
                //     currentMachine.machine.
                // }


                // Update task metrics
                task.runTime = max(task.runTime, ceil(runTimeOnThisMachine).toLong())
                task.energyConsumed += energyConsumptionOnThisMachine
                callbacks.scheduleTaskOnMachine(task, currentMachine.machine, resourcesToUse, resourcesToUse == coresLeft)
                totalFreeCpu -= resourcesToUse
                coresLeft -= resourcesToUse

                if (currentMachine.freeCpus == 0 && machineStates.hasNext()) {
                    currentMachine = machineStates.next()
                }
            }
        }
    }

}