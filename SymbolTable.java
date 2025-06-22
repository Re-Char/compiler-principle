import java.util.HashMap;

public class SymbolTable {
    private HashMap<String, Type> symbolTable;

    public SymbolTable() {
        this.symbolTable = new HashMap<>();
    }

    public void put(String name, Type type) {
        this.symbolTable.put(name, type);
    }

    public Type getType(String name) {
        return this.symbolTable.get(name);
    }

    public boolean contains(String name) {
        return this.symbolTable.containsKey(name);
    }

}
