package com.study.classcardhelper;

interface IShizukuTapService {
    boolean tap(int x, int y) = 1;
    boolean isClassCardForeground() = 2;
    String status() = 3;
    void destroy() = 16777114;
}
