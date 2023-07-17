package xtdb.trie;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.DenseUnionVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;

import java.util.stream.IntStream;

public class ArrowHashTrie {

    private static final byte BRANCH_TYPE_ID = 1;
    private static final byte LEAF_TYPE_ID = 2;

    private final DenseUnionVector nodesVec;
    private final ListVector branchVec;
    private final IntVector branchElVec;
    private final StructVector leafVec;
    private final IntVector pageIdxVec;

    private ArrowHashTrie(VectorSchemaRoot trieRoot) {
        nodesVec = ((DenseUnionVector) trieRoot.getVector("nodes"));
        branchVec = (ListVector) nodesVec.getVectorByType(BRANCH_TYPE_ID);
        branchElVec = (IntVector) branchVec.getDataVector();
        leafVec = (StructVector) nodesVec.getVectorByType(LEAF_TYPE_ID);
        pageIdxVec = leafVec.getChild("page-idx", IntVector.class);
    }

    public interface NodeVisitor<R> {
        R visitBranch(Branch branch);

        R visitLeaf(Leaf leaf);
    }

    public sealed interface Node {
        byte[] path();

        <R> R accept(NodeVisitor<R> visitor);
    }

    private static byte[] conjPath(byte[] path, byte idx) {
        int currentPathLength = path.length;
        var childPath = new byte[currentPathLength + 1];
        System.arraycopy(path, 0, childPath, 0, currentPathLength);
        childPath[currentPathLength] = idx;
        return childPath;
    }

    public final class Branch implements Node {

        private final byte[] path;
        private final int branchVecIdx;

        public Branch(byte[] path, int branchVecIdx) {
            this.path = path;
            this.branchVecIdx = branchVecIdx;
        }

        public Node[] getChildren() {
            return IntStream.range(branchVec.getElementStartIndex(branchVecIdx), branchVec.getElementEndIndex(branchVecIdx))
                .mapToObj(childIdx -> {
                    return branchElVec.isNull(childIdx) ? null : forIndex(conjPath(path, (byte) childIdx), branchElVec.get(childIdx));
                })
                .toArray(Node[]::new);
        }

        @Override
        public byte[] path() {
            return path;
        }

        @Override
        public <R> R accept(NodeVisitor<R> visitor) {
            return visitor.visitBranch(this);
        }
    }

    public final class Leaf implements Node {

        private final byte[] path;
        private final int leafVecIdx;

        public Leaf(byte[] path, int leafVecIdx) {
            this.path = path;
            this.leafVecIdx = leafVecIdx;
        }

        public int getPageIndex() {
            return pageIdxVec.get(leafVecIdx);
        }

        @Override
        public byte[] path() {
            return path;
        }

        @Override
        public <R> R accept(NodeVisitor<R> visitor) {
            return visitor.visitLeaf(this);
        }
    }
    
    private Node forIndex(byte[] path, int idx) {
        var nodeOffset = nodesVec.getOffset(idx);

        return switch (nodesVec.getTypeId(idx)) {
            case 0 -> null;
            case BRANCH_TYPE_ID -> new Branch(path, nodeOffset);
            case LEAF_TYPE_ID -> new Leaf(path, nodeOffset);
            default -> throw new UnsupportedOperationException();
        };
    }

    public static Node from(VectorSchemaRoot trieRoot) {
        return new ArrowHashTrie(trieRoot).forIndex(new byte[0], trieRoot.getRowCount() - 1);
    }
}
