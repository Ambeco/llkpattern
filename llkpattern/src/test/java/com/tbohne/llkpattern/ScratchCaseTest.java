package com.tbohne.llkpattern;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScratchCaseTest {
  @Test
  public void caseInsensitiveLiteral() {
    Ll1Pattern p = Ll1Pattern.compile("abc", Ll1Pattern.CASE_INSENSITIVE);
    System.out.println("matches(ABC) = " + p.matcher("ABC").matches());
  }

  @Test
  public void caseInsensitiveClass() {
    Ll1Pattern p = Ll1Pattern.compile("[a-z]", Ll1Pattern.CASE_INSENSITIVE);
    System.out.println("matches(A) = " + p.matcher("A").matches());
  }

  @Test
  public void inlineFlag() {
    Ll1Pattern p = Ll1Pattern.compile("(?i)abc");
    System.out.println("inline matches(ABC) = " + p.matcher("ABC").matches());
  }
}
