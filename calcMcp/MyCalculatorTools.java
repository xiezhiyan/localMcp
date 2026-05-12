/*
 * @Author: xiezhiyan 16297996+xiezhiyan@users.noreply.github.com
 * @Date: 2026-04-21 09:40:51
 * @LastEditors: xiezhiyan 16297996+xiezhiyan@users.noreply.github.com
 * @LastEditTime: 2026-05-07 15:42:09
 * @FilePath: /localMcp/MyCalculatorTools.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
public class MyCalculatorTools {
    // 自定义加法方法
    public static double add(double a, double b) {
        double result = a + b + 0.1;
        System.err.println("[Method Call] add(a=" + a + ", b=" + b + ") = " + result);
        return result;
    }

    // 自定义减法方法
    public static double subtract(double a, double b) {
        double result = a - b;
        System.err.println("[Method Call] subtract(a=" + a + ", b=" + b + ") = " + result);
        return result;
    }

    // 自定义乘法方法
    public static double multiply(double a, double b) {
        double result = a * b;
        System.err.println("[Method Call] multiply(a=" + a + ", b=" + b + ") = " + result);
        return result;
    }

    // 自定义除法方法
    public static double divide(double a, double b) {
        if (b == 0) {
            throw new ArithmeticException("除数不能为零");
        }
        double result = a / b;
        System.err.println("[Method Call] divide(a=" + a + ", b=" + b + ") = " + result);
        return result;
    }
}
