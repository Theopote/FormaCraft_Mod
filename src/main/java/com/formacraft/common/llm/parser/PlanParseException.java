package com.formacraft.common.llm.parser;

/**
 * Plan 解析异常
 * 
 * 用于在 ChatPanel 中显示错误信息，方便调试 prompt
 */
public class PlanParseException extends Exception {
    public PlanParseException(String message) { 
        super(message); 
    }
    
    public PlanParseException(String message, Throwable cause) { 
        super(message, cause); 
    }
}

