import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;

import java.util.ArrayList;
import java.util.List;

public class LexerErrorListener extends BaseErrorListener {

    private List<String> errors = new ArrayList<>();

    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
        String error = "Error type A at Line " + line + ": " + msg;
        errors.add(error);
    }
    
    public List<String> getErrors() {
        return errors;
    }
}
