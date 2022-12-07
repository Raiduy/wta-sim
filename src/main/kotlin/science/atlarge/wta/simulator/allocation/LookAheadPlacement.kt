package science.atlarge.wta.simulator.allocation

import science.atlarge.wta.simulator.model.Task
import science.atlarge.wta.simulator.model.Ticks
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class LookAheadPlacement : TaskPlacementPolicy {

    override fun scheduleTasks(eligibleTasks: Iterator<Task>, callbacks: AllocationCallbacks, currentTime: Ticks) {
        // Compute the total amount of available resources to exit early
        var totalFreeCpu = callbacks.getNumberOfAvailableResources()
        val freeResourcesPerSlowDown = callbacks.getNumberOfAvailableResourcesPerMachineSpeed()

        // Loop through eligible tasks and try to place them on machines
        while (totalFreeCpu > 0 && eligibleTasks.hasNext()) {
            val task = eligibleTasks.next()
            if (task.cpuDemand > totalFreeCpu) continue

//            require(task.earliestStartTime >= 0) {
//                "A task had a negative earliestStartTime: ${task.id} had ${task.earliestStartTime}"
//            }
//
//            require(currentTime >= task.earliestStartTime) {
//                "A task cannot start earlier than its earliest start time. " +
//                        "Simulation time was $currentTime and earliest time is ${task.earliestStartTime}} " +
//                        "Info: ID: ${task.id} ST: ${task.submissionTime} RT: ${task.runTime}"
//            }

            // Update the task slack given some tasks may have been delayed before, eating up slack of this one.
            val slackLeft = max(0, task.slack - (currentTime - task.earliestStartTime))

            var coresLeft = task.cpuDemand
            val canRejectSlowdownMachines = coresLeft <= freeResourcesPerSlowDown
                    .filter { task.originalRuntime / it.key <= task.originalRuntime + slackLeft }
                    .values
                    .sum()

            // Get a list of machines that can fit this task, in ascending order of energy efficiency
            val machineStates = callbacks.getMachineStatesByAscendingEnergyEfficiency()
            while (coresLeft in 1..totalFreeCpu && machineStates.hasNext()) {
                // Try to place it on the next machine
                val machineState = machineStates.next()
                // Check if the machine is too slow - CORNER CASE: only do this if we can afford to do so
                // if we do not have enough cores otherwise, we cannot avoid using slower machines and break the deadline :(
                // TODO we might be able to compute if resources become available in time to do complete the task within the slack limit,
                //  but this increases the complexity considerably!
                if (canRejectSlowdownMachines && task.originalRuntime / machineState.normalizedSpeed > task.originalRuntime + slackLeft) {
                    continue
                }

                // Set the runtime to the slowest machine
                var runTimeOnThisMachine = task.originalRuntime / machineState.normalizedSpeed
                val resourcesToUse = min(machineState.freeCpus, coresLeft)
                var energyConsumptionOnThisMachine = machineState.TDP.toDouble() /
                        machineState.machine.numberOfCpus *
                        resourcesToUse *
                        (runTimeOnThisMachine / 1000 / 3600)  // ms to seconds to hours to get Wh
                
                var instantConsumptionOnThisMachineJoules = machineState.machine.computeEnergyConsumption(machineState.freeCpus)

                // Check if DVFS is enabled to see if we can get further gains
                if (machineState.dvfsEnabled && runTimeOnThisMachine < (task.originalRuntime + slackLeft)) {
                    val additionalSlowdown =
                        machineState.dvfsOptions.floorKey(
                            // Key = leftover slack + runtime / runtime
                            // Two caveats:
                            // 1) runtime of tasks can be 0, so we set these to 1 as it's likely
                            // that given some slack these tasks can then be still delayed significantly
                            // 2) The minimum slowdown of a task is 1.0. We might hit a special case when
                            // A task's runtime = 0 and slack = 0 which would cause a 0 or negative slowdown.
                            1 + ((task.originalRuntime + slackLeft) - runTimeOnThisMachine) / runTimeOnThisMachine

                        )
                    runTimeOnThisMachine *= additionalSlowdown
                    energyConsumptionOnThisMachine *= (1 - machineState.dvfsOptions[additionalSlowdown]!!)
                    instantConsumptionOnThisMachineJoules *= (1 - machineState.dvfsOptions[additionalSlowdown]!!)
                }

                // Update machine metrics
                machineState.machine.instantEnergyConsumption = instantConsumptionOnThisMachineJoules

                // Update task metrics
                task.runTime = max(task.runTime, ceil(runTimeOnThisMachine).toLong())
                task.energyConsumed += energyConsumptionOnThisMachine
                callbacks.scheduleTaskOnMachine(task, machineState.machine, resourcesToUse, resourcesToUse == coresLeft)
                totalFreeCpu -= resourcesToUse
                coresLeft -= resourcesToUse
            }
        }
    }

}