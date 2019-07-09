package examples.testgeneration.generators.reference;

import edu.illinois.cs.cs125.answerable.api.Generator;
import edu.illinois.cs.cs125.answerable.api.Solution;
import org.junit.jupiter.api.Assertions;

import java.util.Random;

public class OverrideDefaultCtor {

    public OverrideDefaultCtor() {
        Assertions.fail();
    }

    public OverrideDefaultCtor(int unused) {
        // Use this constructor
    }

    @Solution
    public int getSprings() {
        return 0;
    }

    @Generator
    public static OverrideDefaultCtor generate(int complexity, Random random) {
        return new OverrideDefaultCtor(1);
    }

}
