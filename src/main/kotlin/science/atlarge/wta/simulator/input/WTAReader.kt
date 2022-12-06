package science.atlarge.wta.simulator.input

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.longs.LongSet
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter
import org.apache.parquet.filter.ColumnPredicates
import org.apache.parquet.filter.ColumnRecordFilter
import org.apache.parquet.filter2.compat.FilterCompat
import org.apache.parquet.filter2.predicate.FilterApi
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.io.ColumnIOFactory
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.PrimitiveType
import science.atlarge.wta.simulator.model.Ticks
import science.atlarge.wta.simulator.model.Trace
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt
import org.apache.hadoop.fs.Path as HPath

class WTAReader : TraceReader(), SamplingTraceReader {

    override var samplingFraction: Double? = null

    private fun readWorkflows(paths: Iterable<Path>, slack: Long2ObjectOpenHashMap<Long2LongOpenHashMap>): List<WTAWorkflowRecord> {
        // Find all parquet files in "workflows" directories (i.e., find all parts of the "workflows" table)
        val parquetFiles = paths.flatMap { p ->
            p.toFile().resolve("workflows").walk().filter { f ->
                f.isFile && f.extension == "parquet"
            }.toList()
        }

        // Read each parquet file to extract workflow information
        val workflowRecords = arrayListOf<WTAWorkflowRecord>()
        for (parquetFile in parquetFiles) {
            // Open the parquet file and extract its schema
            val parquetReader = ParquetFileReader.open(
                HadoopInputFile.fromPath(
                    HPath(parquetFile.absolutePath), Configuration()
                )
            )
            val schema = parquetReader.fileMetaData.schema

            // Select only the necessary fields from the schema
            val fieldsToSelect = setOf("id", "task_count", "critical_path_length")
            val partialSchema = MessageType(schema.name, schema.fields.filter { f ->
                f.name in fieldsToSelect
            })
            require(partialSchema.fieldCount == fieldsToSelect.size) {
                val missingFields = fieldsToSelect.filter { f -> partialSchema.fields.none { it.name == f } }
                "Missing fields in workflow schema: ${missingFields.joinToString()}"
            }
            // TODO: Remove workarounds when legacy/incorrect trace files are replaced
            val taskCountIsLong = partialSchema.fields.find { it.name == "task_count" }!!.asPrimitiveType()
                .primitiveTypeName == PrimitiveType.PrimitiveTypeName.INT64
            val critPathIsLong = partialSchema.fields.find { it.name == "critical_path_length" }!!.asPrimitiveType()
                .primitiveTypeName == PrimitiveType.PrimitiveTypeName.INT64

            // Prepare a filter to only select valid workflows
            // A critical path length of -1 indicates that a workflow is invalid (contains cycles)
            val filter = if (critPathIsLong) {
                FilterCompat.get(FilterApi.gtEq(FilterApi.longColumn("critical_path_length"), 0L))
            } else {
                FilterCompat.get(FilterApi.gtEq(FilterApi.intColumn("critical_path_length"), 0))
            }

            // Read row groups from the parquet file
            while (true) {
                // Get the next row group and construct a record reader
                val rowGroup = parquetReader.readNextRowGroup() ?: break
                val columnIO = ColumnIOFactory().getColumnIO(partialSchema, schema)
                val recordReader = columnIO.getRecordReader(rowGroup, GroupRecordConverter(partialSchema), filter)

                // Read all rows for the group
                val rowCount = rowGroup.rowCount
                var rowsRead = 0L
                while (rowsRead < rowCount) {
                    // Try reading the next record
                    val record = recordReader.read()
                    rowsRead++

                    // Skip the record if it was filtered out
                    if (recordReader.shouldSkipCurrentRecord()) {
                        continue
                    }

                    // Parse the record and add it
                    val workflowId = record.getLong("id", 0)
                    if (!slack.contains(workflowId)) {
                        continue
                    }
                    val taskCount = if (taskCountIsLong) {
                        record.getLong("task_count", 0).toInt()
                    } else {
                        record.getInteger("task_count", 0)
                    }
                    workflowRecords.add(WTAWorkflowRecord(workflowId, taskCount))
                }
            }
        }

        require(workflowRecords.isNotEmpty()) {
            "Found no workflows in the given trace"
        }

        return workflowRecords
    }

    private fun sampleWorkflows(workflowRecords: List<WTAWorkflowRecord>): LongSet {
        val selectedWorkflowIds = LongOpenHashSet()
        if (samplingFraction == null) {
            for (r in workflowRecords) {
                selectedWorkflowIds.add(r.workflowId)
            }
        } else {
            val totalTasks = workflowRecords.map { it.taskCount }.sum()
            val targetTaskCount = samplingFraction!! * totalTasks

            var currentTaskCount = 0.0
            var currentAbsDelta = targetTaskCount

            val iter = workflowRecords.shuffled().iterator()
            while (iter.hasNext()) {
                val nextWorkflow = iter.next()
                val newTaskCount = currentTaskCount + nextWorkflow.taskCount
                val newAbsDelta = (newTaskCount - targetTaskCount).absoluteValue
                if (newAbsDelta <= currentAbsDelta || selectedWorkflowIds.isEmpty()) {
                    selectedWorkflowIds.add(nextWorkflow.workflowId)
                    currentTaskCount = newTaskCount
                    currentAbsDelta = newAbsDelta
                } else {
                    break
                }
            }
        }

        return selectedWorkflowIds
    }

    private fun readTasks(
        paths: Iterable<Path>,
        workflowFilter: LongSet,
        includeOrphans: Boolean,
        slack: Long2ObjectOpenHashMap<Long2LongOpenHashMap>,
        earliestStartTimes: Long2ObjectOpenHashMap<Long2LongOpenHashMap>
    ): List<WTATaskRecord> {
        // Find all parquet files in "tasks" directories (i.e., find all parts of the "tasks" table)
        val parquetFiles = paths.flatMap { p ->
            p.resolve("tasks").toFile().walk().filter { f ->
                f.isFile && f.extension == "parquet"
            }.toList()
        }

        // Prepare a filter to only select tasks that match the workflow filter
        val filter = FilterCompat.get(
            ColumnRecordFilter.column("workflow_id",
                ColumnPredicates.applyFunctionToLong { workflowId -> workflowId in workflowFilter })
        )

        // Read each parquet file to extract task information
        val taskRecords = arrayListOf<WTATaskRecord>()
        for (parquetFile in parquetFiles) {

            // Open the parquet file and extract its schema
            val parquetReader = ParquetFileReader.open(
                HadoopInputFile.fromPath(
                    HPath(parquetFile.absolutePath), Configuration()
                )
            )
            val schema = parquetReader.fileMetaData.schema

            // Select only the necessary fields from the schema
            val fieldsToSelect =
                setOf("id", "workflow_id", "ts_submit", "runtime", "resource_amount_requested", "parents")
            val partialSchema = MessageType(schema.name, schema.fields.filter { f ->
                f.name in fieldsToSelect
            })
            require(partialSchema.fieldCount == fieldsToSelect.size) {
                val missingFields = fieldsToSelect.filter { f -> partialSchema.fields.none { it.name == f } }
                "Missing fields in workflow schema: ${missingFields.joinToString()}"
            }

            // Read row groups from the parquet file
            while (true) {
                // Get the next row group and construct a record reader
                val rowGroup = parquetReader.readNextRowGroup() ?: break
                val columnIO = ColumnIOFactory().getColumnIO(partialSchema, schema)
                val recordReader = columnIO.getRecordReader(rowGroup, GroupRecordConverter(partialSchema), filter)

                // Read all rows for the group
                val rowCount = rowGroup.rowCount
                var rowsRead = 0L
                while (rowsRead < rowCount) {
                    // Try reading the next record
                    val record = recordReader.read()
                    rowsRead++

                    // Skip the record if it was filtered out
                    if (recordReader.shouldSkipCurrentRecord()) {
                        continue
                    }
                    // If no record was read, we reached the end of the group
                    if (record == null) {
                        break
                    }

                    // Parse the record and add it
                    val taskId = record.getLong("id", 0)
                    val workflowId = record.getLong("workflow_id", 0)
                    val submitTime = record.getLong("ts_submit", 0)
                    // Make sure tasks do not have a runtime of 0 or lower - this causes issues when multiple machines
                    // need to execute a task, and thus it becomes a mix of scheduled - already done events in the sim.
                    val runTime = max(1, record.getLong("runtime", 0))
                    val cores = record.getDouble("resource_amount_requested", 0).roundToInt()
                    val TMP = record.getDouble("resource_amount_requested", 0)
                    if (TMP != cores.toDouble()) {
                        println("Non-integer cores: $TMP")
                    }
                    val dependenciesGroup = record.getGroup("parents", 0)
                    val dependencyCount = dependenciesGroup.getFieldRepetitionCount(0)
                    val dependencies = LongArray(dependencyCount) { i ->
                        dependenciesGroup.getGroup(0, i).getLong(0, 0)
                    }

                    // It may be that some workflow ids are not in the slack computation
                    // These are Bags of Tasks and were filtered out as well as workflows with cycles
                    // - Alibaba is known to have these among others
                    if (!slack.containsKey(workflowId)) continue
                    val taskSlack = slack[workflowId]!![taskId]
                    val earliestStartTime = earliestStartTimes[workflowId]!![taskId]
                    taskRecords.add(
                        WTATaskRecord(
                            workflowId,
                            taskId,
                            submitTime,
                            runTime,
                            cores,
                            dependencies,
                            taskSlack,
                            earliestStartTime
                        )
                    )
                }
            }
        }

        require(taskRecords.isNotEmpty()) {
            "Found no tasks in the given trace ${parquetFiles[0].name} and others"
        }

        return taskRecords
    }

    // TODO make slackFolder optional or only enable with the look_ahead placement policy
    override fun readTraceFromPaths(paths: Iterable<Path>, slackDirectory: Path): Trace {
        println("--- READING TRACE ---")
        val trace = Trace()

        // Read slack files
        val folderName = paths.first().toFile().name.replace("_parquet", "_slack.parquet")
        val (slack, earliestStartTimes) = readSlackFromPath(folderName, slackDirectory)

        // Read workflow data to filter out invalid workflows and sample a subset of the trace (if requested)
        val wfStartTime = System.currentTimeMillis()
        val workflowRecords = readWorkflows(paths, slack)
        val workflowFilter = sampleWorkflows(workflowRecords)
        val includeOrphanTasks = samplingFraction == null
        val wfEndTime = System.currentTimeMillis()
        println("Read workflows in ${wfEndTime - wfStartTime} ms")

        // Create workflows
        for (workflow in workflowFilter.sorted()) {
            trace.createWorkflow(workflow.toString())
        }

        // Read tasks and sort by (workflow name, task name)
        val tsStartTime = System.currentTimeMillis()
        val tasks = readTasks(paths, workflowFilter, includeOrphanTasks, slack, earliestStartTimes).toMutableList()
        val tsEndTime = System.currentTimeMillis()
        println("Read tasks in ${tsEndTime - tsStartTime} ms")
        tasks.sortWith(compareBy(WTATaskRecord::workflowId, WTATaskRecord::taskId))
        // Create tasks
        for (task in tasks) {
            val workflow = if (task.workflowId != null) trace.getWorkflowByName(task.workflowId.toString()) else null
            trace.createTask(
                task.taskId.toString(),
                workflow,
                task.runTime,
                task.submitTime,
                task.slack,
                task.cores,
                task.earliestStartTime
            )
        }
        // Add dependencies
        for (taskRecord in tasks) {
            if (taskRecord.dependencies.isEmpty()) continue
            require(taskRecord.workflowId != null) {
                "A task that does not belong to a workflow cannot have dependencies"
            }

            val workflow = trace.getWorkflowByName(taskRecord.workflowId.toString())
            val task = workflow.getTaskByName(taskRecord.taskId.toString())

            for (dep in taskRecord.dependencies) {
                val depTask = workflow.getTaskByName(dep.toString())
                task.addDependency(depTask)
            }
        }

        return trace
    }

    fun readSlackFromPath(folderName: String, slackDirectory: Path): Pair<Long2ObjectOpenHashMap<Long2LongOpenHashMap>, Long2ObjectOpenHashMap<Long2LongOpenHashMap>>  {
        // Get the workflow slack data
        val slack = Long2ObjectOpenHashMap<Long2LongOpenHashMap>()
        val earliestStartTimes = Long2ObjectOpenHashMap<Long2LongOpenHashMap>()

        val slackFiles = Paths.get(slackDirectory.toFile().absolutePath, folderName).toFile().walk().filter { f ->
            f.isFile && f.extension == "parquet"
        }.toList()

        for (f in slackFiles) {
            val slackReader = ParquetFileReader.open(
                HadoopInputFile.fromPath(
                    HPath(f.absolutePath), Configuration()
                )
            )
            val slackSchema = slackReader.fileMetaData.schema
            while (true) {
                // Get the next row group and construct a record reader
                val rowGroup = slackReader.readNextRowGroup() ?: break
                val columnIO = ColumnIOFactory().getColumnIO(slackSchema)
                val recordReader = columnIO.getRecordReader(rowGroup, GroupRecordConverter(slackSchema))

                // Read all rows for the group
                val rowCount = rowGroup.rowCount
                var rowsRead = 0L
                while (rowsRead < rowCount) {
                    // Try reading the next record
                    val record = recordReader.read()
                    rowsRead++

                    // Skip the record if it was filtered out
                    if (recordReader.shouldSkipCurrentRecord()) {
                        continue
                    }
                    // If no record was read, we reached the end of the group
                    if (record == null) {
                        break
                    }

                    // Parse the record and add it
                    val taskId = record.getLong("task_id", 0)
                    val workflowId = record.getLong("workflow_id", 0)
                    val taskSlack = record.getLong("task_slack", 0)
                    val earliestStartTime = record.getLong("minimal_start_time", 0)
                    slack.getOrPut(workflowId, { Long2LongOpenHashMap() })[taskId] = taskSlack
                    earliestStartTimes.getOrPut(workflowId, { Long2LongOpenHashMap() })[taskId] = earliestStartTime
                }
            }
        }

        return Pair(slack, earliestStartTimes)
    }

}

private class WTAWorkflowRecord(
    val workflowId: Long,
    val taskCount: Int
)

private class WTATaskRecord(
    val workflowId: Long?,
    val taskId: Long,
    val submitTime: Ticks,
    val runTime: Ticks,
    val cores: Int,
    val dependencies: LongArray,
    val slack: Long,
    val earliestStartTime: Long
)