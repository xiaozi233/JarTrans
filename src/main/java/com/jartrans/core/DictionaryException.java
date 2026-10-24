package com.jartrans.core;

/** 词典操作失败（名称非法/重名/不存在/文件不可用等），消息面向用户可直接展示。 */
public class DictionaryException extends Exception {

    private static final long serialVersionUID = 1L;

    public DictionaryException(String message) {
        super(message);
    }

    public DictionaryException(String message, Throwable cause) {
        super(message, cause);
    }
}
