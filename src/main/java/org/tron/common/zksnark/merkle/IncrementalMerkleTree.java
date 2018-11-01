package org.tron.common.zksnark.merkle;


import com.google.common.collect.Lists;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.tron.common.zksnark.SHA256Compress;

public class IncrementalMerkleTree {

  public static Integer DEPTH = 29;

  private Optional<SHA256Compress> left;
  private Optional<SHA256Compress> right;

  public int DynamicMemoryUsage() {
    return 32 + // left
        32 + // right
        parents.size() * 32; // parents
  }

  public int size() {

    int ret = 0;
    if (left.isPresent()) {
      ret++;
    }
    if (right.isPresent()) {
      ret++;
    }
    // Treat occupation of parents array as a binary number
    // (right-shifted by 1)
    for (int i = 0; i < parents.size(); i++) {
      if (parents.get(i).isPresent()) {
        ret += (1 << (i + 1));
      }
    }
    return ret;
  }

  public void append(SHA256Compress obj) {

    if (isComplete(DEPTH)) {
      throw new RuntimeException("tree is full");
    }

    if (!left.isPresent()) {
      // Set the left leaf
      left = Optional.of(obj);
    } else if (!right.isPresent()) {
      // Set the right leaf
      right = Optional.of(obj);
    } else {
      // Combine the leaves and propagate it up the tree
      Optional<SHA256Compress> combined = Optional.of(
          SHA256Compress.combine(left.get(), right.get(), 0));

      // Set the "left" leaf to the object and make the "right" leaf none
      left = Optional.of(obj);
      right = Optional.empty();

      for (int i = 0; i < DEPTH; i++) {
        if (i < parents.size()) {
          if (parents.get(i).isPresent()) {
            combined = Optional.of(
                SHA256Compress.combine(parents.get(i).get(), combined.get(), i + 1));
            parents.set(i, Optional.empty());
          } else {
            parents.set(i, combined);
            break;
          }
        } else {
          parents.add(combined);
          break;
        }
      }
    }
  }

  public SHA256Compress root() {
    return root(DEPTH, new ArrayDeque<SHA256Compress>());
  }

  public SHA256Compress last() {

    if (right.isPresent()) {
      return right.get();
    } else if (left.isPresent()) {
      return left.get();
    } else {
      throw new RuntimeException("tree has no cursor");
    }
  }

  //构造IncrementalWitness
  public IncrementalWitness witness() {
    return new IncrementalWitness(this);
  }

  public static SHA256Compress empty_root() {
    return EmptyMerkleRoots.emptyMerkleRootsInstance.emptyRoot(DEPTH);
  }

  // Collapsed "left" subtrees ordered toward the root of the tree.
  private List<Optional<SHA256Compress>> parents;

  private MerklePath path() {
    Deque<SHA256Compress> filler_hashes = new ArrayDeque<SHA256Compress>();
    return path(filler_hashes);
  }

  public MerklePath path(Deque<SHA256Compress> filler_hashes) {

    if (!left.isPresent()) {
      throw new RuntimeException(
          "can't create an authentication path for the beginning of the tree");
    }

    PathFiller filler = new PathFiller(filler_hashes);

    List<SHA256Compress> path = new ArrayList<>();
    List<Boolean> index = new ArrayList<>();

    if (right.isPresent()) {
      //所在位置是右支时为true,左支时为false
      index.add(true);
      path.add(left.get());
    } else {
      //右支为空时填充
      index.add(false);
      path.add(filler.next(0));
    }

    int d = 1;

    for (Optional<SHA256Compress> parent : parents) {
      if (parent.isPresent()) {
        index.add(true);
        path.add(parent.get());
      } else {
        index.add(false);
        path.add(filler.next(d));
      }

      d++;
    }

    while (d < DEPTH) {
      index.add(false);
      path.add(filler.next(d));
      d++;
    }

    List<List<Boolean>> merkle_path = new ArrayList<>();

    for (SHA256Compress b : path) {
      merkle_path.add(MerkleUtils.convertBytesVectorToVector(b.getContent()));
    }
    Lists.reverse(merkle_path);
    Lists.reverse(index);

    return new MerklePath(merkle_path, index);
  }

  public SHA256Compress root(int depth) {
    Deque<SHA256Compress> filler_hashes = new ArrayDeque<SHA256Compress>();
    return root(depth, filler_hashes);
  }

  public SHA256Compress root(int depth, Deque<SHA256Compress> filler_hashes) {

    PathFiller filler = new PathFiller(filler_hashes);

    SHA256Compress combine_left = left.isPresent() ? left.get() : filler.next(0);
    SHA256Compress combine_right = right.isPresent() ? right.get() : filler.next(0);

    SHA256Compress root = SHA256Compress.combine(combine_left, combine_right, 0);

    int d = 1;
    for (Optional<SHA256Compress> parent : parents) {
      if (parent.isPresent()) {
        root = SHA256Compress.combine(parent.get(), root, d);
      } else {
        root = SHA256Compress.combine(root, filler.next(d), d);
      }

      d++;
    }

    // We may not have parents for ancestor trees, so we fill
    // the rest in here.
    while (d < depth) {
      root = SHA256Compress.combine(root, filler.next(d), d);
      d++;
    }

    return root;
  }


  private boolean isComplete() {
    return isComplete(DEPTH);
  }

  public boolean isComplete(int depth) {

    if (!left.isPresent() || !right.isPresent()) {
      return false;
    }

    if (parents.size() != (depth - 1)) {
      return false;
    }

    for (Optional<SHA256Compress> parent : parents) {
      if (!parent.isPresent()) {
        return false;
      }
    }

    return true;
  }

  public int next_depth(int skip) {

    if (!left.isPresent()) {
      if (skip != 0) {
        skip--;
      } else {
        return 0;
      }
    }

    if (!right.isPresent()) {
      if (skip != 0) {
        skip--;
      } else {
        return 0;
      }
    }

    int d = 1;

    for (Optional<SHA256Compress> parent : parents) {
      if (!parent.isPresent()) {
        if (skip != 0) {
          skip--;
        } else {
          return d;
        }
      }

      d++;
    }
    return d + skip;
  }

  private void wfcheck() {
    if (parents.size() >= DEPTH) {
      throw new RuntimeException("tree has too many parents");
    }

    //不能往parent中填充null
    // The last parent cannot be null.
    if ((!parents.isEmpty()) && (!parents.get(parents.size() - 1).isPresent())) {
      throw new RuntimeException("tree has non-canonical representation of parent");
    }

    //先填充left再填充right
    // Left cannot be empty when right exists.
    if ((!left.isPresent()) && right.isPresent()) {
      throw new RuntimeException("tree has non-canonical representation; right should not exist");
    }

    //先填充left,再填充parents
    // Left cannot be empty when parents is nonempty.
    if ((!left.isPresent()) && parents.size() > 0) {
      throw new RuntimeException(
          "tree has non-canonical representation; parents should not be unempty");
    }
  }

}
