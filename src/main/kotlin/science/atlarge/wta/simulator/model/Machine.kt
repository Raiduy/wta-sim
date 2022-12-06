package science.atlarge.wta.simulator.model

typealias MachineId = Int

class Machine(
    val id: MachineId,
    val name: String,
    val cluster: Cluster,
    val numberOfCpus: Int,
    val dvfsEnabled: Boolean,
    val normalizedSpeed: Double,
    val TDP: Int
) {
    var powerEfficiency: Double

    var idleTDP: Int // in Watts
    
    var instantEnergyConsumption: Double // In Joules

    init {
        cluster.addMachine(this)
        powerEfficiency = (TDP.toDouble() / numberOfCpus) * normalizedSpeed

        idleTDP = 100 * 9 // idle TDP is 100W, and there are 9 machines

        // calculate idle consumption
        instantEnergyConsumption = idleEnergyConsumption(numberOfCpus)
    }

    fun idleEnergyConsumption(numOfIdleCpus: Int): Double {
        var res = idleTDP.toDouble() / numberOfCpus * numOfIdleCpus * 900
        // println(" - - - - - - idleTDP function is ${idleTDP.toDouble()} - - - - - - ")
        // println(" - - - - - - numberOfCpus function is ${numberOfCpus} - - - - - - ")
        // println(" - - - - - - numOfIdleCpus function is ${numOfIdleCpus} - - - - - - ")
        // println(" - - - - - - idleEnergy function is ${res} - - - - - - ")
        return res
    }

    override fun equals(other: Any?): Boolean {
        return this === other
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "Machine(id=$id, name='$name', cluster=${cluster.id}, cpus=$numberOfCpus)"
    }

    fun idString(): String {
        return "Machine(id=$id, name='$name', cluster=${cluster.id})"
    }

}