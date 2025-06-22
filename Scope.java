import java.util.Stack;

public class Scope {
    private Stack<SymbolTable> stack;

    public Scope() {
        this.stack = new Stack<>();
        this.stack.push(new SymbolTable());
    }

    public void enterNewScope() {
        this.stack.push(new SymbolTable());
    }

    public void exitScope() {
        this.stack.pop();
    }

    public void put(String name, Type type) {
        this.stack.peek().put(name, type);
    }

    public Type getType(String name) {
        for (int i = this.stack.size() - 1; i >= 0; i--) {
            SymbolTable symbolTable = this.stack.get(i);
            if (symbolTable.contains(name)) {
                return symbolTable.getType(name);
            }
        }
        return null;
    }

    public boolean contains(String name) {
        for (int i = this.stack.size() - 1; i >= 0; i--) {
            SymbolTable symbolTable = this.stack.get(i);
            if (symbolTable.contains(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsInCurScope(String name) {
        return this.stack.peek().contains(name);
    }

}
