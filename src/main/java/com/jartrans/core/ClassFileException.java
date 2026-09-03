package com.jartrans.core;

/** class 文件解析/重写过程中的结构性错误。 */
public class ClassFileException extends Exception {

    public ClassFileException(String message) {
        super(message);
    }
}
