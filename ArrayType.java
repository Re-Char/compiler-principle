public class ArrayType extends Type {
    private Type contained;
    private int count;

    public ArrayType(Type contained, int count) {
        this.contained = contained;
        this.count = count;
    }
    
    public Type getContained() {
        return contained;
    }

    public int getCount() {
        return count;
    }

    @Override
    public String getTypeName() {
        return "ARRAY";
    }
}
