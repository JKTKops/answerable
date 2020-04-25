package examples.testgeneration.validation.reference;

import edu.illinois.cs.cs125.answerable.DefaultTestRunArguments;
import edu.illinois.cs.cs125.answerable.api.TestOutput;
import edu.illinois.cs.cs125.answerable.Verify;

public class ArgsOnStandaloneVerify {

    @Verify(standalone = true)
    @DefaultTestRunArguments(numTests = 96)
    public static void verify(TestOutput<ArgsOnStandaloneVerify> ours, TestOutput<ArgsOnStandaloneVerify> theirs) {
        // Do nothing
    }

}
