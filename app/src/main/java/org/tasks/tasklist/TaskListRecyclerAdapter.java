package org.tasks.tasklist;

import static androidx.recyclerview.widget.ItemTouchHelper.DOWN;
import static androidx.recyclerview.widget.ItemTouchHelper.LEFT;
import static androidx.recyclerview.widget.ItemTouchHelper.RIGHT;
import static androidx.recyclerview.widget.ItemTouchHelper.UP;
import static com.todoroo.andlib.utility.AndroidUtilities.assertMainThread;
import static com.todoroo.andlib.utility.AndroidUtilities.assertNotMainThread;

import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.DiffUtil.DiffResult;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;
import com.todoroo.astrid.activity.TaskListFragment;
import com.todoroo.astrid.adapter.TaskAdapter;
import com.todoroo.astrid.api.CaldavFilter;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.api.GtasksFilter;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.utility.Flags;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import org.tasks.data.TaskContainer;
import org.tasks.intents.TaskIntents;
import org.tasks.tasklist.ViewHolder.ViewHolderCallbacks;

public class TaskListRecyclerAdapter extends RecyclerView.Adapter<ViewHolder>
    implements ViewHolderCallbacks, ListUpdateCallback {

  private static final int LONG_LIST_SIZE = 500;

  private final TaskAdapter adapter;
  private final TaskListFragment taskList;
  private final RecyclerView recyclerView;
  private final ViewHolderFactory viewHolderFactory;
  private final boolean isRemoteList;
  private final TaskDao taskDao;
  private List<TaskContainer> list;
  private PublishSubject<List<TaskContainer>> publishSubject = PublishSubject.create();
  private CompositeDisposable disposables = new CompositeDisposable();
  private Queue<Pair<List<TaskContainer>, DiffResult>> updates = new LinkedList<>();
  private boolean dragging;

  public TaskListRecyclerAdapter(
      TaskAdapter adapter,
      RecyclerView recyclerView,
      ViewHolderFactory viewHolderFactory,
      TaskListFragment taskList,
      List<TaskContainer> list,
      TaskDao taskDao) {
    this.adapter = adapter;
    this.recyclerView = recyclerView;
    this.viewHolderFactory = viewHolderFactory;
    this.taskList = taskList;
    isRemoteList =
        taskList.getFilter() instanceof GtasksFilter
            || taskList.getFilter() instanceof CaldavFilter;
    this.list = list;
    this.taskDao = taskDao;
    new ItemTouchHelper(new ItemTouchHelperCallback()).attachToRecyclerView(recyclerView);
    Pair<List<TaskContainer>, DiffResult> initial = Pair.create(list, null);
    disposables.add(
        publishSubject
            .observeOn(Schedulers.computation())
            .scan(initial, this::calculateDiff)
            .skip(1)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(this::applyDiff));
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    return viewHolderFactory.newViewHolder(parent, this);
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    TaskContainer task = getItem(position);
    if (task != null) {
      holder.bindView(task, isRemoteList, adapter.supportsManualSorting());
      holder.setMoving(false);
      int indent = adapter.getIndent(task);
      task.setIndent(indent);
      holder.setIndent(indent);
      holder.setSelected(adapter.isSelected(task));
    }
  }

  @Override
  public void onCompletedTask(TaskContainer task, boolean newState) {
    adapter.onCompletedTask(task, newState);
    taskList.loadTaskListContent();
  }

  @Override
  public void onClick(ViewHolder viewHolder) {
    if (taskList.isActionModeActive()) {
      toggle(viewHolder);
    } else {
      taskList.onTaskListItemClicked(viewHolder.task.getTask());
    }
  }

  @Override
  public void onClick(Filter filter) {
    if (!taskList.isActionModeActive()) {
      FragmentActivity context = taskList.getActivity();
      if (context != null) {
        context.startActivity(TaskIntents.getTaskListIntent(context, filter));
      }
    }
  }

  @Override
  public boolean onLongPress(ViewHolder viewHolder) {
    if (!adapter.supportsParentingOrManualSort()) {
      taskList.startActionMode();
    }
    if (taskList.isActionModeActive() && !viewHolder.isMoving()) {
      toggle(viewHolder);
    }
    return true;
  }

  @Override
  public void toggleSubtasks(TaskContainer task, boolean collapsed) {
    taskDao.setCollapsed(task.getId(), collapsed);
    taskList.broadcastRefresh();
  }

  private void toggle(ViewHolder viewHolder) {
    adapter.toggleSelection(viewHolder.task);
    notifyItemChanged(viewHolder.getAdapterPosition());
    if (adapter.getSelected().isEmpty()) {
      taskList.finishActionMode();
    } else {
      taskList.updateModeTitle();
    }
  }

  public TaskContainer getItem(int position) {
    return list.get(position);
  }

  public void submitList(List<TaskContainer> list) {
    publishSubject.onNext(list);
  }

  @Override
  public void onInserted(int position, int count) {
    notifyItemRangeInserted(position, count);
  }

  @Override
  public void onRemoved(int position, int count) {
    notifyItemRangeRemoved(position, count);
  }

  @Override
  public void onMoved(int fromPosition, int toPosition) {
    LinearLayoutManager layoutManager =
        (LinearLayoutManager) Objects.requireNonNull(recyclerView.getLayoutManager());
    View firstChild = layoutManager.getChildAt(0);
    int firstChildPosition = layoutManager.findFirstVisibleItemPosition();

    notifyItemMoved(fromPosition, toPosition);

    if (firstChildPosition > 0 && firstChild != null) {
      layoutManager.scrollToPositionWithOffset(firstChildPosition - 1, firstChild.getTop());
    } else if (firstChildPosition >= 0) {
      layoutManager.scrollToPosition(firstChildPosition);
    }
  }

  @Override
  public void onChanged(int position, int count, @Nullable Object payload) {
    notifyItemRangeChanged(position, count, payload);
  }

  private Pair<List<TaskContainer>, DiffResult> calculateDiff(
      Pair<List<TaskContainer>, DiffResult> last, List<TaskContainer> next) {
    assertNotMainThread();

    DiffCallback cb = new DiffCallback(last.first, next, adapter);
    boolean shortList = next.size() < LONG_LIST_SIZE;
    boolean calculateDiff = last.first.size() != next.size() || shortList;
    DiffResult result = calculateDiff ? DiffUtil.calculateDiff(cb, shortList) : null;

    return Pair.create(next, result);
  }

  private void applyDiff(Pair<List<TaskContainer>, DiffResult> update) {
    assertMainThread();

    updates.add(update);

    if (!dragging) {
      drainQueue();
    }
  }

  private void drainQueue() {
    assertMainThread();

    Pair<List<TaskContainer>, DiffResult> update = updates.poll();
    while (update != null) {
      list = update.first;
      if (update.second == null) {
        notifyDataSetChanged();
      } else {
        update.second.dispatchUpdatesTo((ListUpdateCallback) this);
      }
      update = updates.poll();
    }
  }

  @Override
  public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
    disposables.dispose();
  }

  @Override
  public int getItemCount() {
    return list.size();
  }

  private class ItemTouchHelperCallback extends ItemTouchHelper.Callback {
    private int from = -1;
    private int to = -1;

    @Override
    public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
      super.onSelectedChanged(viewHolder, actionState);
      if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
        taskList.startActionMode();
        ((ViewHolder) viewHolder).setMoving(true);
        dragging = true;
        int position = viewHolder.getAdapterPosition();
        updateIndents((ViewHolder) viewHolder, position, position);
      }
    }

    @Override
    public int getMovementFlags(
        @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      return adapter.supportsParentingOrManualSort() && adapter.getNumSelected() == 0
          ? makeMovementFlags(UP | DOWN | LEFT | RIGHT, 0)
          : makeMovementFlags(0, 0);
    }

    @Override
    public boolean onMove(
        @NonNull RecyclerView recyclerView,
        @NonNull RecyclerView.ViewHolder src,
        @NonNull RecyclerView.ViewHolder target) {
      taskList.finishActionMode();
      int fromPosition = src.getAdapterPosition();
      int toPosition = target.getAdapterPosition();
      ViewHolder source = (ViewHolder) src;
      if (!adapter.canMove(source, (ViewHolder) target)) {
        return false;
      }
      if (from == -1) {
        source.setSelected(false);
        from = fromPosition;
      }
      to = toPosition;
      notifyItemMoved(fromPosition, toPosition);
      updateIndents(source, from, to);
      return true;
    }

    private void updateIndents(ViewHolder source, int from, int to) {
      TaskContainer task = source.task;
      source.setMinIndent(
          to == 0 || to == getItemCount() - 1
              ? 0
              : adapter.minIndent(from <= to ? to + 1 : to, task));
      source.setMaxIndent(to == 0 ? 0 : adapter.maxIndent(from >= to ? to - 1 : to, task));
    }

    @Override
    public void onChildDraw(
        @NonNull Canvas c,
        @NonNull RecyclerView recyclerView,
        @NonNull RecyclerView.ViewHolder viewHolder,
        float dX,
        float dY,
        int actionState,
        boolean isCurrentlyActive) {
      ViewHolder vh = (ViewHolder) viewHolder;
      TaskContainer task = vh.task;
      float shiftSize = vh.getShiftSize();
      if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
        int currentIndent = ((ViewHolder) viewHolder).getIndent();
        int maxIndent = vh.getMaxIndent();
        int minIndent = vh.getMinIndent();
        if (isCurrentlyActive) {
          float dxAdjusted;
          if (dX > 0) {
            dxAdjusted = Math.min(dX, (maxIndent - currentIndent) * shiftSize);
          } else {
            dxAdjusted = Math.max((currentIndent - minIndent) * -shiftSize, dX);
          }

          int targetIndent = currentIndent + Float.valueOf(dxAdjusted / shiftSize).intValue();

          if (targetIndent != task.getIndent()) {
            if (from == -1) {
              taskList.finishActionMode();
              vh.setSelected(false);
            }
          }
          if (targetIndent < minIndent) {
            task.setTargetIndent(minIndent);
          } else if (targetIndent > maxIndent) {
            task.setTargetIndent(maxIndent);
          } else {
            task.setTargetIndent(targetIndent);
          }
        }

        dX = (task.getTargetIndent() - task.getIndent()) * shiftSize;
      }
      super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
    }

    @Override
    public void clearView(
        @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
      super.clearView(recyclerView, viewHolder);
      ViewHolder vh = (ViewHolder) viewHolder;
      vh.setMoving(false);
      dragging = false;
      drainQueue();
      if (taskList.isActionModeActive()) {
        toggle(vh);
      } else {
        TaskContainer task = vh.task;
        int targetIndent = task.getTargetIndent();
        if (from >= 0 && from != to) {
          if (from < to) {
            to++;
          }
          vh.task.setIndent(targetIndent);
          vh.setIndent(targetIndent);
          moved(from, to, targetIndent);
        } else if (task.getIndent() != targetIndent) {
          int position = vh.getAdapterPosition();
          vh.task.setIndent(targetIndent);
          vh.setIndent(targetIndent);
          moved(position, position, targetIndent);
        }
      }
      from = -1;
      to = -1;
      Flags.checkAndClear(Flags.TLFP_NO_INTERCEPT_TOUCH);
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
      throw new UnsupportedOperationException();
    }

    private void moved(int from, int to, int indent) {
      adapter.moved(from, to, indent);
      TaskContainer task = list.remove(from);
      list.add(from < to ? to - 1 : to, task);
      taskList.loadTaskListContent();
    }
  }
}
