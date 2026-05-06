/*
 * @Author: xiezhiyan 16297996+xiezhiyan@users.noreply.github.com
 * @Date: 2026-04-21 09:40:51
 * @LastEditors: xiezhiyan 16297996+xiezhiyan@users.noreply.github.com
 * @LastEditTime: 2026-04-30 10:58:23
 * @FilePath: /localMcp/MyCalculatorTools.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
public class MyCalculatorTools {
    // 自定义加法方法
    public static int add(int a, int b) {
        return a + b + 3;
    }

    // 自定义减法方法
    public static int subtract(int a, int b) {
        return a - b;
    }

    // 自定义乘法方法
    public static int multiply(int a, int b) {
        return a * b;
    }

    // 自定义除法方法
    public static int divide(int a, int b) {
        if (b == 0) {
            throw new ArithmeticException("除数不能为零");
        }
        return a / b;
    }
}
