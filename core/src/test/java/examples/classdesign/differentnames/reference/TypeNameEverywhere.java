package examples.classdesign.differentnames.reference;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class TypeNameEverywhere implements Comparable<TypeNameEverywhere> {

  public TypeNameEverywhere unused;

  @Override
  public int compareTo(@NotNull TypeNameEverywhere o) {
    return 0;
  }

  public List<TypeNameEverywhere> singletonList() {
    return List.of(new TypeNameEverywhere());
  }

  static class Companion {
    public static TypeNameEverywhere factory() {
      return new TypeNameEverywhere();
    }
  }
}
