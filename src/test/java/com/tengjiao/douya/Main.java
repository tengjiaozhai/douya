package com.tengjiao.douya;

/**
 * test
 *
 * @author 沈鸣杰
 * @since 2026-02-12 09:23
 */

import java.util.Scanner;
// 20 1  1出现12次
// 注意类名必须为 Main, 不要有任何 package xxx 信息
public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        // 注意 hasNext 和 hasNextLine 的区别
        while (in.hasNextInt()) { // 注意 while 处理多个 case
            int a = in.nextInt();
            int b = in.nextInt();
            int count = 0;
            for (int i = 1; i <= a; i++) {
                for (int j = 0; j < String.valueOf(i).length(); j++) {
                    if (String.valueOf(i).charAt(j) == (b+48)){
                        count++;
                    }
                }
            }
            System.out.println(count);
        }
    }
}