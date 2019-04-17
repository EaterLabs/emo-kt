package me.eater.emo.utils

import kotlinx.coroutines.*

class WorkflowBuilder<C> {
    private var processes: HashMap<String, Process<C>> = hashMapOf()
    private var steps: HashMap<String, ProcessChain<C>> = hashMapOf()
    private var start: String? = null

    fun bind(process: Process<C>) {
        processes[process.getName()] = process
    }

    fun start(step: String) {
        start = step
    }

    fun step(process: String, router: Router<C>, name: String = process) {
        if (steps.containsKey(name)) {
            throw Exception("Workflow already has a step with the name $name.")
        }

        this.steps[name] = ProcessChain(process, router)
    }

    fun step(process: String, nextStep: String?, name: String = process) {
        step(process, StaticRouter(nextStep), name)
    }

    fun step(process: String, router: (context: C) -> String?, name: String = process) {
        step(process, object : Router<C> {
            override fun route(context: C): String? {
                return router(context)
            }
        }, name)
    }

    fun step(process: Process<C>, router: Router<C>, name: String = process.getName()) {
        bind(process)
        step(process.getName(), router, name)
    }

    fun step(process: Process<C>, nextStep: String?, name: String = process.getName()) {
        bind(process)
        step(process.getName(), nextStep, name)
    }

    fun step(process: Process<C>, router: (context: C) -> String?, name: String = process.getName()) {
        bind(process)
        step(process.getName(), router, name)
    }

    fun build(context: C): Workflow<C> {
        return Workflow(processes, steps, start!!, context)
    }
}

data class ProcessChain<C>(val process: String, val router: Router<C>)

interface Router<C> {
    fun route(context: C): String?
}

class StaticRouter<C>(private val step: String?) : Router<C> {
    override fun route(context: C): String? {
        return step
    }
}

data class ProcessStartedEvent<C>(val step: String, val process: Process<C>, val router: Router<C>)
data class WorkflowEvent<C>(val workflow: Workflow<C>)

class Workflow<C>(
    private val processes: HashMap<String, Process<C>>,
    private val steps: HashMap<String, ProcessChain<C>>,
    private val start: String,
    var context: C
) {
    private var currentStep: String = start
    private var executionJob: Deferred<Unit>? = null
    var finished: Boolean = false

    val processStarted: Event<ProcessStartedEvent<C>> = Event()
    val workflowFinished: Event<WorkflowEvent<C>> = Event()

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

        processStarted(ProcessStartedEvent(currentStep, process, router))
        job.await()
        tryNext(router)
    }

    private suspend fun tryNext(router: Router<C>) {
        val nextStep = router.route(context)

        if (nextStep === null) {
            finished = true
            workflowFinished(WorkflowEvent(this))
            return
        }

        currentStep = nextStep
        tryProcess()
    }

    fun waitFor() {
        runBlocking {
            executionJob?.await()
        }
    }
}

interface Process<in C> {
    fun getName(): String
    suspend fun execute(context: C)
}

class Noop : Process<Any> {
    override fun getName(): String {
        return "noop"
    }

    override suspend fun execute(context: Any) {}
}