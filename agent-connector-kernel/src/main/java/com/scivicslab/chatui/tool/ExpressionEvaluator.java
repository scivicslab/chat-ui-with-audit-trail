package com.scivicslab.chatui.tool;

/**
 * Evaluates the argument of {@code calc} once {@code CalcExpression} has accepted it as arithmetic. The app passes
 * one backed by JShell; a build without JShell (a native image) can pass an arithmetic-only one.
 */
@FunctionalInterface
public interface ExpressionEvaluator {

    /** @return the value of {@code expression}, or a text starting with {@code error: } */
    String evaluate(String expression);
}
