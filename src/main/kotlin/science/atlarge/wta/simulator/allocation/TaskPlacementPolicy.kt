package science.atlarge.wta.simulator.allocation

import it.unimi.dsi.fastutil.doubles.Double2IntOpenHashMap
import science.atlarge.wta.simulator.model.Machine
import science.atlarge.wta.simulator.model.Task
import science.atlarge.wta.simulator.model.Ticks
import science.atlarge.wta.simulator.state.MachineState

interface TaskPlacementPolicy {

    fun scheduleTasks(eligibleTasks: Iterator<Task>, callbacks: AllocationCallbacks, currentTime: Ticks)

}

interface AllocationCallbacks {

    fun scheduleTaskOnMachine(task: Task, machine: Machine, resources: Int, taskCompletelyScheduled: Boolean)

    fun getMachineStates(): Iterator<MachineState>

    fun getMachineStatesByAscendingFreeCpu(): Iterator<MachineState>

    fun getMachineStatesByAscendingFreeCpu(minimumFreeCpu: Int): Iterator<MachineState>

    fun getMachineStatesByAscendingEnergyEfficiency(): Iterator<MachineState>
    fun getMachineStatesByDescendingMachineSpeed(): Iterator<MachineState>

    fun getMachineStatesByDescendingFreeCpu(): Iterator<MachineState>

    fun getNumberOfAvailableResources(): Int
    fun getNumberOfAvailableResourcesPerMachineSpeed(): Double2IntOpenHashMap
}