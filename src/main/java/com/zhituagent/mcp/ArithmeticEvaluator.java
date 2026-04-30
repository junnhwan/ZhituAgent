package com.zhituagent.mcp;

/**
 * Tiny shunting-yard arithmetic evaluator used by {@link MockMcpClient}'s
 * {@code calculator} tool. Supports + - * / and parentheses on doubles. We
 * deliberately don't pull in a full expression library — the goal is a
 * predictable in-process MCP demo, not a production calculator.
 */
final class ArithmeticEvaluator {

    private ArithmeticEvaluator() {
    }

    static double evaluate(String expression) {
        Parser parser = new Parser(expression);
        double result = parser.parseExpression();
        parser.expectEnd();
        return result;
    }

    private static final class Parser {
        private final String input;
        private int pos = 0;

        Parser(String input) {
            this.input = input;
        }

        double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipWhitespace();
                if (peek('+')) {
                    pos++;
                    value += parseTerm();
                } else if (peek('-')) {
                    pos++;
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipWhitespace();
                if (peek('*')) {
                    pos++;
                    value *= parseFactor();
                } else if (peek('/')) {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipWhitespace();
            if (peek('(')) {
                pos++;
                double value = parseExpression();
                skipWhitespace();
                if (!peek(')')) {
                    throw new IllegalArgumentException("expected ')' at position " + pos);
                }
                pos++;
                return value;
            }
            if (peek('-')) {
                pos++;
                return -parseFactor();
            }
            return parseNumber();
        }

        private double parseNumber() {
            skipWhitespace();
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("expected number at position " + pos);
            }
            return Double.parseDouble(input.substring(start, pos));
        }

        private boolean peek(char ch) {
            return pos < input.length() && input.charAt(pos) == ch;
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        void expectEnd() {
            skipWhitespace();
            if (pos != input.length()) {
                throw new IllegalArgumentException("unexpected trailing input at position " + pos);
            }
        }
    }
}
