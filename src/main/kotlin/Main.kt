package edu.illinois.cs.cs125.answerable

import kotlin.random.Random

fun main(args: Array<String>) {
    val name = args[0]

    val prefix = args.elementAtOrElse(1) {""} // for testing

    val reference = getSolutionClass("${prefix}reference.$name")
    val submission = getAttemptClass("$prefix$name")

    if (reference.isClassDesignReference()) {
        ClassDesignAnalysis(reference, submission).runSuite()
    }

    val refMethod = reference.getReferenceSolutionMethod()
    val tg = TestGenerator(refMethod, submission.findSolutionAttemptMethod(refMethod), reference.getCustomVerifier())

    println(tg.runTests(Random.nextLong()).toJson())
}