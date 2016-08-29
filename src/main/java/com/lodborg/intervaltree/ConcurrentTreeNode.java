package com.lodborg.intervaltree;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListSet;

@SuppressWarnings("Duplicates")
public class ConcurrentTreeNode<T extends Comparable<? super T>>{
	private final NavigableSet<Interval<T>> decreasing, increasing;
	private volatile ConcurrentTreeNode<T> left, right;
	private final T midpoint;
	private volatile int height;
	private final PromotableReadWriteLock leftLock = new PromotableReadWriteLock();
	private final PromotableReadWriteLock rightLock = new PromotableReadWriteLock();

	private ConcurrentTreeNode(ConcurrentTreeNode<T> mirror){
		decreasing = mirror.decreasing;
		increasing = mirror.increasing;
		midpoint = mirror.midpoint;
		height = mirror.height;
		left = mirror.left;
		right = mirror.right;
	}

	public ConcurrentTreeNode(Interval<T> interval){
		decreasing = new TreeSet<>(Interval.endComparator);
		increasing = new TreeSet<>(Interval.startComparator);
		decreasing.add(interval);
		increasing.add(interval);

		midpoint = interval.getMidpoint();
		height = 1;
	}

	public void addInterval(Interval<T> interval) throws InterruptedException {
		if (interval.contains(midpoint)) {
			synchronized (increasing) {
				decreasing.add(interval);
				increasing.add(interval);
			}
			return;
		}

		if (interval.isLeftOf(midpoint)) {
			if (left == null) {
				leftLock.writeLock();
				if (left == null) {
					left = new ConcurrentTreeNode<>(interval);
					leftLock.unlock();
					return;
				}
				leftLock.unlock();
			}

			leftLock.readLock();
			if (left == null){
				leftLock.unlock();
				addInterval(interval);
			}
			left.addInterval(interval);
			if (leftLock.promoteToWriteIfLast()){
				left = left.balanceOut();
			}
			leftLock.unlock();
		} else {
			if (right == null){
				rightLock.writeLock();
				if (right == null) {
					right = new ConcurrentTreeNode<>(interval);
					rightLock.unlock();
					return;
				}
				rightLock.unlock();
			}

			rightLock.readLock();
			if (right == null){
				rightLock.unlock();
				addInterval(interval);
			}
			right.addInterval(interval);
			if (rightLock.promoteToWriteIfLast()){
				right = right.balanceOut();
			}
			rightLock.unlock();
		}
	}

	public int height(){
		return height;
	}

	private static int height(ConcurrentTreeNode node){
		return node == null ? 0 : node.height;
	}

	protected ConcurrentTreeNode<T> balanceOut(){
		if (increasing.size() == 0) {
			ConcurrentTreeNode<T> next = deleteNode(this);
			return next != null ? next.balanceOut() : null;
		}
		height = Math.max(height(left), height(right)) + 1;
		int balance = height(left) - height(right);
		if (balance < -1) {
			// The tree is right-heavy.
			if (height(right.left) > height(right.right)) {
				this.right = this.right.rightRotate();
				return leftRotate();
			} else {
				return leftRotate();
			}
		} else if (balance > 1) {
			// The tree is left-heavy.
			if (height(left.right) > height(left.left)) {
				this.left = this.left.leftRotate();
				return rightRotate();
			} else
				return rightRotate();
		} else {
			// The tree is already balanced.
			return this;
		}
	}

	private ConcurrentTreeNode<T> leftRotate(){
		ConcurrentTreeNode<T> mirrorThis = new ConcurrentTreeNode<>(this);
		ConcurrentTreeNode<T> head = new ConcurrentTreeNode<>(right);
		mirrorThis.right = head.left;
		mirrorThis = mirrorThis.balanceOut();
		head.left = head.assimilateOverlappingIntervals(mirrorThis);
		return head;
	}

	private ConcurrentTreeNode<T> rightRotate(){
		ConcurrentTreeNode<T> mirrorThis = new ConcurrentTreeNode<>(this);
		ConcurrentTreeNode<T> head = new ConcurrentTreeNode<>(left);
		mirrorThis.left = head.right;
		mirrorThis = mirrorThis.balanceOut();
		head.right = head.assimilateOverlappingIntervals(mirrorThis);
		return head;
	}

	private ConcurrentTreeNode<T> assimilateOverlappingIntervals(ConcurrentTreeNode<T> from) {
		if (from.increasing.size() == 0)
			return deleteNode(from);
		ArrayList<Interval<T>> tmp = new ArrayList<>();

		if (midpoint.compareTo(from.midpoint) < 0) {
			for (Interval<T> next : from.increasing) {
				if (next.isRightOf(midpoint))
					break;
				tmp.add(next);
			}
		} else {
			for (Interval<T> next : from.decreasing) {
				if (next.isLeftOf(midpoint))
					break;
				tmp.add(next);
			}
		}

		from.increasing.removeAll(tmp);
		from.decreasing.removeAll(tmp);
		increasing.addAll(tmp);
		decreasing.addAll(tmp);
		if (from.increasing.size() == 0) {
			return deleteNode(from);
		}

		return from;
	}

	public static <T extends Comparable<? super T>> List<Interval<T>> query(ConcurrentTreeNode<T> root, T point, List<Interval<T>> res) {
		if (root == null)
			return res;
		if (point.compareTo(root.midpoint) <= 0){
			for (Interval<T> next: root.increasing){
				if (next.isRightOf(point))
					break;
				res.add(next);
			}
			return ConcurrentTreeNode.query(root.left, point, res);
		} else{
			for (Interval<T> next: root.decreasing){
				if (next.isLeftOf(point))
					break;
				res.add(next);
			}
			return ConcurrentTreeNode.query(root.right, point, res);
		}
	}

	protected boolean removeInterval(Interval<T> interval) throws InterruptedException {
		if (interval.contains(midpoint)){
			synchronized (increasing) {
				decreasing.remove(interval);
				increasing.remove(interval);
			}
			return true;
		}

		if (interval.isLeftOf(midpoint)){
			leftLock.readLock();
			if (left == null){
				leftLock.unlock();
				return false;
			}
			if (left.removeInterval(interval)){
				if(leftLock.promoteToWriteIfLast())
					left = left.balanceOut();
			}
			leftLock.unlock();
		} else {
			rightLock.readLock();
			if (right == null){
				rightLock.unlock();
				return false;
			}
			if (right.removeInterval(interval)){
				if (rightLock.promoteToWriteIfLast())
					right = right.balanceOut();
			}
			rightLock.unlock();
		}

		return true;
	}

	/*public static <T extends Comparable<? super T>> ConcurrentTreeNode<T> removeInterval(ConcurrentTreeNode<T> root, Interval<T> interval) {
		if (root == null)
			return null;
		if (interval.contains(root.midpoint)){
			root.decreasing.remove(interval);
			root.increasing.remove(interval);
			if (root.increasing.size() == 0){
				return deleteNode(root);
			}

		} else if (interval.isLeftOf(root.midpoint)){
			root.left = removeInterval(root.left, interval);
		} else {
			root.right = removeInterval(root.right, interval);
		}
		return root.balanceOut();
	}*/

	private static <T extends Comparable<? super T>> ConcurrentTreeNode<T> deleteNode(ConcurrentTreeNode<T> root) {
		if (root == null || root.left == null && root.right == null)
			return null;
		if (root.left == null){
			ConcurrentTreeNode<T> mirrorRight = new ConcurrentTreeNode<>(root.right);
			ConcurrentTreeNode<T> node = mirrorRight;
			ConcurrentTreeNode<T> parent = null;
			while (node.left != null){
				parent = node;
				node.left = new ConcurrentTreeNode<>(node.left);
				node = node.left;
			}
			if (parent != null) {
				parent.left = node.right;
				node.right = mirrorRight;
			}

			ConcurrentTreeNode<T> newRoot = node;
			if (parent != null) {
				node = mirrorRight;
				while (node != null) {
					newRoot.assimilateOverlappingIntervals(node);
					node = node.left;
				}
			}
			return newRoot.balanceOut();
		} else {
			ConcurrentTreeNode<T> mirrorLeft = new ConcurrentTreeNode<>(root.left);
			ConcurrentTreeNode<T> node = mirrorLeft;
			ConcurrentTreeNode<T> parent = null;
			while (node.right != null){
				parent = node;
				node.right = new ConcurrentTreeNode<>(node.right);
				node = node.right;
			}
			if (parent != null) {
				parent.right = node.left;
				node.left = mirrorLeft;
			}
			node.right = root.right;

			ConcurrentTreeNode<T> newRoot = node;
			node = node.left;
			while (node != null){
				newRoot.assimilateOverlappingIntervals(node);
				node = node.right;
			}
			return newRoot.balanceOut();
		}
	}
}