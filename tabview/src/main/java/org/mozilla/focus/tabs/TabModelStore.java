package org.mozilla.focus.tabs;

import android.content.Context;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.mozilla.focus.persistence.TabModel;
import org.mozilla.focus.persistence.TabsDatabase;
import org.mozilla.focus.utils.FileUtils;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TabModelStore {

    private static final String PREF_KEY_TAB_ID = "pref_key_focus_tab_id";
    private static final String TAB_WEB_VIEW_STATE_FOLDER_NAME = "tabs_cache";

    private static volatile TabModelStore instance;
    private TabsDatabase tabsDatabase;

    public interface AsyncQueryListener {
        void onQueryComplete(List<TabModel> tabModelList, String focusTabId);
    }

    public interface AsyncSaveListener {
        void onSaveComplete();
    }

    private TabModelStore(@NonNull final Context context) {
        tabsDatabase = TabsDatabase.getInstance(context);
    }

    public static TabModelStore getInstance(@NonNull final Context context) {
        if (instance == null) {
            synchronized (TabModelStore.class) {
                if (instance == null) {
                    instance = new TabModelStore(context);
                }
            }
        }
        return instance;
    }

    public void getSavedTabs(@NonNull final Context context, @Nullable final AsyncQueryListener listener) {
        //new QueryTabsTask(context, tabsDatabase, listener).executeOnExecutor(SERIAL_EXECUTOR);
        if (listener != null) {
            listener.onQueryComplete(new ArrayList<TabModel>(), null);
        }
    }

    public void saveTabs(@NonNull final Context context,
                         @NonNull final List<TabModel> tabModelList,
                         @Nullable final String focusTabId,
                         @Nullable final AsyncSaveListener listener) {

        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_KEY_TAB_ID, focusTabId)
                .apply();

        //new SaveTabsTask(context, tabsDatabase, listener).executeOnExecutor(SERIAL_EXECUTOR, tabModelList.toArray(new TabModel[0]));
        if (listener != null) {
            listener.onSaveComplete();
        }
    }

    private static class QueryTabsTask extends AsyncTask<Void, Void, List<TabModel>> {

        private WeakReference<Context> contextRef;
        private TabsDatabase tabsDatabase;
        private WeakReference<AsyncQueryListener> listenerRef;

        public QueryTabsTask(Context context, TabsDatabase tabsDatabase, AsyncQueryListener listener) {
            this.contextRef = new WeakReference<>(context);
            this.tabsDatabase = tabsDatabase;
            this.listenerRef = new WeakReference<>(listener);
        }

        @Override
        protected List<TabModel> doInBackground(Void... voids) {
            if (tabsDatabase != null) {
                List<TabModel> tabModelList = tabsDatabase.tabDao().getTabs();
                Context context = contextRef.get();
                if (context != null && tabModelList != null) {
                    restoreWebViewState(context, tabModelList);
                }
                return tabModelList;
            }

            return null;
        }

        private void restoreWebViewState(@NonNull Context context, @NonNull List<TabModel> tabModelList) {
            File cacheDir = new File(context.getCacheDir(), TAB_WEB_VIEW_STATE_FOLDER_NAME);
            for (TabModel tabModel : tabModelList) {
                if (tabModel != null) {
                    tabModel.setWebViewState(FileUtils.readBundleFromStorage(cacheDir, tabModel.getId()));
                }
            }
        }

        @Override
        protected void onPostExecute(List<TabModel> tabModelList) {
            Context context = contextRef.get();
            AsyncQueryListener listener = listenerRef.get();
            if (listener != null && context != null) {
                String focusTabId = PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(PREF_KEY_TAB_ID, "");
                listener.onQueryComplete(tabModelList, focusTabId);
            }
        }
    }

    private static class SaveTabsTask extends AsyncTask<TabModel, Void, Void> {

        private WeakReference<Context> contextRef;
        private TabsDatabase tabsDatabase;
        private WeakReference<AsyncSaveListener> listenerRef;

        public SaveTabsTask(Context context, TabsDatabase tabsDatabase, AsyncSaveListener listener) {
            this.contextRef = new WeakReference<>(context);
            this.tabsDatabase = tabsDatabase;
            this.listenerRef = new WeakReference<>(listener);
        }

        @Override
        protected Void doInBackground(TabModel... tabModelList) {
            if (tabsDatabase != null) {
                tabsDatabase.tabDao().deleteAllTabs();

                if (tabModelList != null) {
                    Context context = contextRef.get();
                    if (context != null) {
                        saveWebViewState(context, tabModelList);
                    }

                    tabsDatabase.tabDao().insertTabs(tabModelList);
                }
            }

            return null;
        }

        private void saveWebViewState(@NonNull Context context, @NonNull TabModel[] tabModelList) {
            final File cacheDir = new File(context.getCacheDir(), TAB_WEB_VIEW_STATE_FOLDER_NAME);
            final List<File> updateFileList = new ArrayList<>();

            for (TabModel tabModel : tabModelList) {
                if (tabModel != null && tabModel.getWebViewState() != null) {
                    FileUtils.writeBundleToStorage(cacheDir, tabModel.getId(), tabModel.getWebViewState());
                    updateFileList.add(new File(cacheDir, tabModel.getId()));
                }
            }

            // Remove the out-of-date WebView state cache file
            File[] cacheFiles = cacheDir.listFiles();
            if (cacheFiles != null) {
                List<File> outOfDateFileList = new ArrayList<>(Arrays.asList(cacheFiles));
                outOfDateFileList.removeAll(updateFileList);
                boolean success = true;
                for (File file : outOfDateFileList) {
                    success &= file.delete();
                }
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            AsyncSaveListener listener = listenerRef.get();
            if (listener != null) {
                listener.onSaveComplete();
            }
        }
    }
}