import java.io.File;
import java.io.IOException;
import java.util.List;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ListTokenSource;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("input path is required");
        }
        String source = args[0];
        CharStream input = CharStreams.fromFileName(source);
        SysYLexer sysYLexer = new SysYLexer(input);
        LexerErrorListener customLexerErrorListener = new LexerErrorListener();
        sysYLexer.removeErrorListeners();
        sysYLexer.addErrorListener(customLexerErrorListener);
        List<? extends Token> myTokens = sysYLexer.getAllTokens();
        List<String> lexerErrors = customLexerErrorListener.getErrors();
        if (!lexerErrors.isEmpty()) {
            for (String error : lexerErrors) {
                System.err.println(error);
            }
        } else {
            for (Token t : myTokens) {
                int type = t.getType();
                int line = t.getLine();
                String str = t.getText();
                if (type == 34) {
                    if (str.contains("x") || str.contains("X")) {
                        str = String.valueOf((int)Long.parseLong(str.substring(2), 16));
                    } else if (str.charAt(0) == '0') {
                        str = String.valueOf(Integer.parseInt(str, 8));
                    }
                }
                // System.err.println(SysYLexer.ruleNames[type - 1] + " " + str + " " + "at Line " + line + ".");
            }
        }
        ListTokenSource tokenSource = new ListTokenSource(myTokens);
        CommonTokenStream tokens = new CommonTokenStream(tokenSource);
        SysYParser sysYParser = new SysYParser(tokens);
        sysYParser.removeErrorListeners();
        ParserErrorListener customParserErrorListener = new ParserErrorListener();
        sysYParser.addErrorListener(customParserErrorListener);
        ParseTree tree = sysYParser.program();
        // // VisitorForParser visitorForParser = new VisitorForParser();
        // List<String> parserErrors = customParserErrorListener.getErrors();
        // if (!parserErrors.isEmpty()) {
        //     for (String error : parserErrors) {
        //         System.out.println(error);
        //     }
        // } else {
        //     // visitorForParser.visit(tree);
        //     // visitorForParser.Print();
        // }
        // VisitorForGrammar visitorForGrammar = new VisitorForGrammar();
        // visitorForGrammar.visit(tree);
        // List<String> grammarErrors = visitorForGrammar.getErrors();
        // if (grammarErrors.isEmpty())
        //     System.err.println("No semantic errors in the program!");
        // else {
        //     for (String err : grammarErrors) {
        //         System.err.println(err);
        //     }
        VisitorForIR visitorForIR = new VisitorForIR();
        visitorForIR.visit(tree);
        // visitorForIR.generateIR(new File(args[1]));
        OptimizedIR optimizedIR = new OptimizedIR(visitorForIR.getModule());
        optimizedIR.generateOptimizedIR(new File(args[1]));
    }
}