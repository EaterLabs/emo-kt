package me.eater.emo.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * Workflow builder, used to create a new workflow
 */
class WorkflowBuilder<C> {
    private var processes: HashMap<String, Process<C>> = hashMapOf()
    private var steps: HashMap<String, ProcessChain<C>> = hashMapOf()
    private var start: String? = null

    /**
     * Bind new [Process] to this Workflow
     */
    fun bind(process: Process<C>) {
        processes[process.getName()] = process
    }

    /**
     * Set start step
     */
    fun start(step: String) {
        start = step
    }

    /**
     * Add new step
     *
     * @param process Which process should be used in this step
     * @param router The router to use after executing this step
     * @param name The name of this step
     * @param description The description of this step
     */
    fun step(
        process: String,
        router: Router<C>,
        name: String = process,
        description: String = processes[process]!!.getDescription()
    ) {
        if (steps.containsKey(name)) {
            throw Exception("Workflow already has a step with the name $name.")
        }

        this.steps[name] = ProcessChain(process, router, name, description)
    }

    /**
     * Add new step
     *
     * @param process Which process should be used in this step
     * @param nextStep The step that should be executed after this, if null this is the last step in the workflow
     * @param name The name of this step
     * @param description The description of this step
     */
    fun step(
        process: String,
        nextStep: String?,
        name: String = process,
        description: String = processes[process]!!.getDescription()
    ) {
        step(process, StaticRouter(nextStep), name, description)
    }

    /**
     * Add new step
     *
     * @param process Which process should be used in this step
     * @param router Function that selects which step should be executed after this, if it returns null this is the last step in the workflow
     * @param name The name of this step
     * @param description The description of this step
     */
    fun step(
        process: String,
        router: (context: C) -> String?,
        name: String = process,
        description: String = processes[process]!!.getDescription()
    ) {
        step(process, object : Router<C> {
            override fun route(context: C): String? {
                return router(context)
            }
        }, name, description)
    }

    /**
     * Add new step
     *
     * @param process Process that should be used in this step, is also immediately bound to this workflow
     * @param router The router to use after executing this step
     * @param name The name of this step
     * @param description The description of this step
     */
    fun step(
        process: Process<C>,
        router: Router<C>,
        name: String = process.getName(),
        description: String = process.getDescription()
    ) {
        bind(process)
        step(process.getName(), router, name, description)
    }

    /**
     * Add new step
     *
     * @param process Process that should be used in this step, is also immediately bound to this workflow
     * @param nextStep The step that should be executed after this, if null this is the last step in the workflow
     * @param name The name of this step
     * @param description The description of this step
     */
    fun step(
        process: Process<C>,
        nextStep: String?,
        name: String = process.getName(),
        description: String = process.getDescription()
    ) {
        bind(process)
        step(process.getName(), nextStep, name, description)
    }

    /**
     * Add new step
     *
     * @param process Process that should be used in this step, is also immediately bound to this workflow
     * @param router Function that selects which step should be executed after this, if it returns null this is the last step in the workflow
     * @param name The name of this step
     * @param description The description of this step
     */
    fun step(
        process: Process<C>,
        router: (context: C) -> String?,
        name: String = process.getName(),
        description: String = process.getDescription()
    ) {
        bind(process)
        step(process.getName(), router, name, description)
    }

    /**
     * Build the workflow with given context
     */
    fun build(context: C): Workflow<C> {
        return Workflow(processes, steps, start!!, context)
    }
}

/**
 * Process chain, contains the name of the [process], the router, the name of this step and the description of this step
 */
data class ProcessChain<C>(
    /**
     * Process to use
     */
    val process: String,
    /**
     * Router to select next step
     */
    val router: Router<C>,
    /**
     * Name of this step
     */
    val name: String = process,

    /**
     * Description of what this step does
     */
    val description: String = ""
)

/**
 * Used to select the next step in a workflow
 */
interface Router<C> {
    /**
     * Function that returns the next step, or null if this is supposed to be the last step
     */
    fun route(context: C): String?
}

/**
 * [Router] that always returns the same result
 *
 * @param step The next step that this router should return
 */
class StaticRouter<C>(private val step: String?) : Router<C> {
    override fun route(context: C): String? {
        return step
    }
}

/**
 * Event that is executed when a new process is started in a workflow
 */
data class ProcessStartedEvent<C>(
    /**
     * Name of step that was started
     */
    val step: String,

    /**
     * [Process] that is started
     */
    val process: Process<C>,

    /**
     * [Router] for this step
     */
    val router: Router<C>,

    /**
     * Description of this step
     */
    val description: String = process.getDescription()
)

/**
 * Generic workflow event
 * @param workflow workflow that emitted this event
 */
data class WorkflowEvent<C>(val workflow: Workflow<C>)

/**
 * Workflow object
 */
class Workflow<C>(
    /**
     * Processes used in this workflow, indexed by name
     */
    private val processes: HashMap<String, Process<C>>,

    /**
     * Steps for this workflow, indexed by name
     */
    private val steps: HashMap<String, ProcessChain<C>>,

    /**
     * First step in this workflow
     */
    private val start: String,

    /**
     * Context of this workflow
     */
    var context: C
) {
    private var currentStep: String = start
    private var executionJob: Deferred<Unit>? = null
    var finished: Boolean = false

    val processStarted: Event<ProcessStartedEvent<C>> = Event()
    val workflowFinished: Event<WorkflowEvent<C>> = Event()

    /**
     * Run this workflow in given [CoroutineScope], default is [GlobalScope]
     */
    fun execute(executionScope: CoroutineScope = GlobalScope) {
        if (executionJob !== null) {
            return
        }

        executionJob = executionScope.async {
            tryProcess()
        }
    }

    private suspend fun tryProcess() {
        val chain = steps[currentStep]

        if (chain === null) {
            throw Error("Step $currentStep does not exist")
        }

        val process = processes[chain.process]
        if (process === null) {
            throw Error("Process $currentStep does not exist")
        }

        val router = chain.router

        val job = GlobalScope.async {
            process.execute(context)
        }

        processStarted.handle(ProcessStartedEvent(currentStep, process, router, chain.description))
        job.await()
        tryNext(router)
    }

    private suspend fun tryNext(router: Router<C>) {
        val nextStep = router.route(context)

        if (nextStep === null) {
            finished = true
            workflowFinished.handle(WorkflowEvent(this))
            return
        }

        currentStep = nextStep
        tryProcess()
    }

    /**
     * Suspend until workflow is done, may throw if workflow failed
     */
    suspend fun waitFor() {
        executionJob?.await()
    }
}

/**
 * Process interface
 */
interface Process<in C> {
    /**
     * Get name of this process
     */
    fun getName(): String

    /**
     * Get description of this process
     */
    fun getDescription(): String

    /**
     * Start this process
     */
    suspend fun execute(context: C)
}

/**
 * Noop process, useful when only routing is needed
 */
class Noop : Process<Any> {
    override fun getName(): String {
        return "noop"
    }

    override fun getDescription() = "No-op process."

    override suspend fun execute(context: Any) {}
}