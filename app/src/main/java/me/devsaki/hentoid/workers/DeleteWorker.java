package me.devsaki.hentoid.workers;

import static me.devsaki.hentoid.util.GroupHelper.moveBook;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.WorkerParameters;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

import me.devsaki.hentoid.R;
import me.devsaki.hentoid.database.CollectionDAO;
import me.devsaki.hentoid.database.ObjectBoxDAO;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.Group;
import me.devsaki.hentoid.events.ProcessEvent;
import me.devsaki.hentoid.notification.delete.DeleteCompleteNotification;
import me.devsaki.hentoid.notification.delete.DeleteProgressNotification;
import me.devsaki.hentoid.notification.delete.DeleteStartNotification;
import me.devsaki.hentoid.util.ContentHelper;
import me.devsaki.hentoid.util.GroupHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.exception.ContentNotRemovedException;
import me.devsaki.hentoid.util.exception.FileNotRemovedException;
import me.devsaki.hentoid.util.notification.Notification;
import me.devsaki.hentoid.workers.data.DeleteData;


/**
 * Worker responsible for deleting content in the background
 */
public class DeleteWorker extends BaseWorker {

    private final long[] contentIds;
    private final long[] contentPurgeIds;
    private final long[] groupIds;
    private final long[] queueIds;
    private final int deleteMax;
    private final boolean isDeleteGroupsOnly;

    private int deleteProgress;
    private int nbError;

    private final CollectionDAO dao;

    public DeleteWorker(
            @NonNull Context context,
            @NonNull WorkerParameters parameters) {
        super(context, parameters, R.id.delete_service, "delete");

        DeleteData.Parser inputData = new DeleteData.Parser(getInputData());
        contentIds = inputData.getContentIds();
        contentPurgeIds = inputData.getContentPurgeIds();
        groupIds = inputData.getGroupIds();
        queueIds = inputData.getQueueIds();
        isDeleteGroupsOnly = inputData.isDeleteGroupsOnly();
        deleteMax = contentIds.length + contentPurgeIds.length + groupIds.length + queueIds.length;

        dao = new ObjectBoxDAO(context);
    }

    @Override
    Notification getStartNotification() {
        return new DeleteStartNotification(deleteMax, (deleteMax == contentPurgeIds.length));
    }

    @Override
    void onInterrupt() {
        // Nothing to do here
    }

    @Override
    void onClear() {
        dao.cleanup();
    }

    @Override
    void getToWork(@NonNull Data input) {
        deleteProgress = 0;
        nbError = 0;

        // First chain contents, then groups (to be sure to delete empty groups only)
        if (contentIds.length > 0) removeContentList(contentIds);
        if (contentPurgeIds.length > 0) purgeContentList(contentPurgeIds);
        if (groupIds.length > 0) removeGroups(groupIds, isDeleteGroupsOnly);
        if (queueIds.length > 0) removeQueue(queueIds);

        progressDone();
    }

    private void removeContentList(long[] ids) {
        List<Content> contents = dao.selectContent(ids);

        // Flag the content as "being deleted" (triggers blink animation)
        for (Content c : contents) flagContentDelete(c, true);
        // Delete them
        for (Content c : contents) {
            deleteContent(c);
            if (isStopped()) break;
        }
    }

    /**
     * Delete the given content
     *
     * @param content Content to be deleted
     */
    private void deleteContent(@NonNull final Content content) {
        Helper.assertNonUiThread();
        progressItem(content, false);
        try {
            ContentHelper.removeContent(getApplicationContext(), dao, content);
            trace(Log.INFO, "Removed item: %s from database and file system.", content.getTitle());
        } catch (ContentNotRemovedException cnre) {
            nbError++;
            trace(Log.WARN, "Error when trying to delete %s", content.getId());
        } catch (Exception e) {
            nbError++;
            trace(Log.WARN, "Error when trying to delete %s : %s", content.getTitle(), e.getMessage());
        }
    }

    private void purgeContentList(long[] ids) {
        List<Content> contents = dao.selectContent(ids);
        for (Content c : contents) {
            purgeContent(c);
            if (isStopped()) break;
        }
    }

    /**
     * Purge the given content
     *
     * @param content Content to be purged
     */
    private void purgeContent(@NonNull final Content content) {
        Helper.assertNonUiThread();
        progressItem(content, true);
        try {
            ContentHelper.purgeFiles(getApplicationContext(), content, false);
            trace(Log.INFO, "Purged item: %s.", content.getTitle());
        } catch (Exception e) {
            nbError++;
            trace(Log.WARN, "Error when trying to purge %s : %s", content.getTitle(), e.getMessage());
        }
    }

    private void removeGroups(long[] ids, boolean deleteGroupsOnly) {
        List<Group> groups = dao.selectGroups(ids);
        try {
            for (Group g : groups) {
                deleteGroup(g, deleteGroupsOnly);
                if (isStopped()) break;
            }
        } finally {
            GroupHelper.updateGroupsJson(getApplicationContext(), dao);
        }
    }

    /**
     * Delete the given group
     * WARNING : If the group contains GroupItems, it will be ignored
     * This method is aimed to be used to delete empty groups when using Custom grouping
     *
     * @param group Group to be deleted
     */
    private void deleteGroup(@NonNull final Group group, boolean deleteGroupsOnly) {
        Helper.assertNonUiThread();

        Group theGroup = group;
        progressItem(theGroup, false);

        try {
            // Reassign group for contained items
            if (deleteGroupsOnly) {
                List<Content> containedContentList = theGroup.getContents();
                for (Content c : containedContentList) {
                    Content movedContent = moveBook(c, null, dao);
                    ContentHelper.updateContentJson(getApplicationContext(), movedContent);
                }
                theGroup = dao.selectGroup(theGroup.id);
            }
            if (theGroup != null) {
                if (!theGroup.items.isEmpty()) {
                    nbError++;
                    trace(Log.WARN, "Group is not empty : %s", theGroup.name);
                    return;
                }
                dao.deleteGroup(theGroup.id);
                trace(Log.INFO, "Removed group: %s from database.", theGroup.name);
            }
        } catch (Exception e) {
            nbError++;
            trace(Log.WARN, "Error when trying to delete group %d : %s", group.id, e.getMessage());
        }
    }

    private void removeQueue(long[] ids) {
        List<Content> contents = dao.selectContent(ids);
        try {
            for (Content c : contents) {
                removeQueuedContent(c);
                if (isStopped()) break;
            }
        } finally {
            if (ContentHelper.updateQueueJson(getApplicationContext(), dao))
                trace(Log.INFO, "Queue JSON successfully saved");
            else trace(Log.WARN, "Queue JSON saving failed");
        }
    }

    private void removeQueuedContent(@NonNull final Content content) {
        try {
            progressItem(content, false);
            ContentHelper.removeQueuedContent(getApplicationContext(), dao, content);
        } catch (ContentNotRemovedException e) {
            // Don't throw the exception if we can't remove something that isn't there
            if (!(e instanceof FileNotRemovedException && content.getStorageUri().isEmpty())) {
                nbError++;
                trace(Log.WARN, "Error when trying to delete queued %s : %s", content.getTitle(), e.getMessage());
            }
        }
    }

    private void progressItem(Object item, boolean isPurge) {
        String title = null;
        if (item instanceof Content) title = ((Content) item).getTitle();
        else if (item instanceof me.devsaki.hentoid.database.domains.Group)
            title = ((me.devsaki.hentoid.database.domains.Group) item).name;

        if (title != null) {
            deleteProgress++;
            notificationManager.notify(new DeleteProgressNotification(title, deleteProgress + nbError, deleteMax, isPurge));
            EventBus.getDefault().post(new ProcessEvent(ProcessEvent.EventType.PROGRESS, R.id.generic_delete, 0, deleteProgress, nbError, deleteMax));
        }
    }

    private void progressDone() {
        notificationManager.notify(new DeleteCompleteNotification(deleteMax, nbError > 0));
        EventBus.getDefault().post(new ProcessEvent(ProcessEvent.EventType.COMPLETE, R.id.generic_delete, 0, deleteProgress, nbError, deleteMax));
    }

    /**
     * Set the "being deleted" flag of the given content
     *
     * @param content Content whose flag to set
     * @param flag    Value of the flag to be set
     */
    public void flagContentDelete(@NonNull final Content content, boolean flag) {
        content.setIsBeingDeleted(flag);
        dao.insertContent(content);
    }
}
