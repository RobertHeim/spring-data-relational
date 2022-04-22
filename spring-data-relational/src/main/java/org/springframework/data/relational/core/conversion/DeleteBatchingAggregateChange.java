package org.springframework.data.relational.core.conversion;

import org.springframework.data.mapping.PersistentPropertyPath;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.util.Collections.*;

/**
 * A {@link BatchingAggregateChange} implementation for delete changes that can contain actions for one or more delete
 * operations. When consumed, actions are yielded in the appropriate entity tree order with deletes carried out from
 * leaves to root. All operations that can be batched are grouped and combined to offer the ability for an optimized
 * batch operation to be used.
 *
 * @author Chirag Tailor
 * @since 3.0
 */
public class DeleteBatchingAggregateChange<T> implements BatchingAggregateChange<T, DeleteAggregateChange<T>> {

	private static final Comparator<PersistentPropertyPath<RelationalPersistentProperty>> pathLengthComparator = //
			Comparator.comparing(PersistentPropertyPath::getLength);

	private final Class<T> entityType;
	private final Map<Number, List<DbAction.DeleteRoot<T>>> rootActions = new HashMap<>();
	private final List<DbAction.AcquireLockRoot<?>> lockActions = new ArrayList<>();
	private final Map<PersistentPropertyPath<RelationalPersistentProperty>, List<DbAction.Delete<Object>>> deleteActions = //
			new HashMap<>();

	public DeleteBatchingAggregateChange(Class<T> entityType) {
		this.entityType = entityType;
	}

	@Override
	public Kind getKind() {
		return Kind.DELETE;
	}

	@Override
	public Class<T> getEntityType() {
		return entityType;
	}

	@Override
	public void forEachAction(Consumer<? super DbAction<?>> consumer) {

		lockActions.forEach(consumer);
		deleteActions.entrySet().stream().sorted(Map.Entry.comparingByKey(pathLengthComparator.reversed()))
				.forEach((entry) -> {
					List<DbAction.Delete<Object>> deletes = entry.getValue();
					if (deletes.size() > 1) {
						consumer.accept(new DbAction.BatchDelete<>(deletes));
					} else {
						deletes.forEach(consumer);
					}
				});
		rootActions.forEach((previousVersion, deleteRoots) -> {
			if (deleteRoots.size() > 1) {
				consumer.accept(previousVersion != null ? new DbAction.BatchDeleteRootWithVersion<>(deleteRoots)
						: new DbAction.BatchDeleteRoot<>(deleteRoots));
			} else {
				deleteRoots.forEach(consumer);
			}
		});
	}

	@Override
	public void add(DeleteAggregateChange<T> aggregateChange) {

		aggregateChange.forEachAction(action -> {
			if (action instanceof DbAction.DeleteRoot<?> deleteRootAction) {
				// noinspection unchecked
				addDeleteRoot((DbAction.DeleteRoot<T>) deleteRootAction);
			} else if (action instanceof DbAction.Delete<?> deleteAction) {
				// noinspection unchecked
				addDelete((DbAction.Delete<Object>) deleteAction);
			} else if (action instanceof DbAction.AcquireLockRoot<?> lockRootAction) {
				lockActions.add(lockRootAction);
			}
		});
	}

	private void addDelete(DbAction.Delete<Object> action) {

		PersistentPropertyPath<RelationalPersistentProperty> propertyPath = action.getPropertyPath();
		deleteActions.merge(propertyPath, new ArrayList<>(singletonList(action)), (actions, defaultValue) -> {
			actions.add(action);
			return actions;
		});
	}

	private void addDeleteRoot(DbAction.DeleteRoot<T> action) {

		Number previousVersion = action.getPreviousVersion();
		rootActions.merge(previousVersion, new ArrayList<>(singletonList(action)), (actions, defaultValue) -> {
			actions.add(action);
			return actions;
		});
	}
}
