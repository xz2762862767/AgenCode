package com.nebula.aichat.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话持久化：整个会话集序列化为一个 JSON 文件保存到应用私有目录。
 * 写盘在单线程池里做，写入使用临时文件 + rename，避免中途崩溃导致文件损坏。
 */
public class ChatStore {

    private static final String TAG = "ChatStore";
    private static final String FILE_NAME = "chats.json";
    private static ChatStore INSTANCE;

    private final File file;
    private final List<Conversation> conversations = new ArrayList<>();
    private final Object lock = new Object();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ChatStore(Context ctx) {
        this.file = new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
        load();
    }

    public static synchronized ChatStore get(Context ctx) {
        if (INSTANCE == null) INSTANCE = new ChatStore(ctx.getApplicationContext());
        return INSTANCE;
    }

    private void load() {
        if (!file.exists()) return;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } catch (Exception e) {
            Log.w(TAG, "读取会话失败", e);
            return;
        }
        try {
            JSONArray arr = new JSONArray(sb.toString());
            synchronized (lock) {
                conversations.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) conversations.add(Conversation.fromJson(o));
                }
                sortLocked();
            }
        } catch (Exception e) {
            Log.w(TAG, "解析会话失败", e);
        }
    }

    private void sortLocked() {
        Collections.sort(conversations, new Comparator<Conversation>() {
            @Override
            public int compare(Conversation a, Conversation b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
    }

    /** 按更新时间倒序返回快照 */
    public List<Conversation> list() {
        synchronized (lock) {
            sortLocked();
            return new ArrayList<>(conversations);
        }
    }

    public Conversation create() {
        Conversation c = new Conversation();
        synchronized (lock) {
            conversations.add(0, c);
        }
        save();
        return c;
    }

    public Conversation get(String id) {
        if (id == null) return null;
        synchronized (lock) {
            for (Conversation c : conversations) {
                if (id.equals(c.id)) return c;
            }
        }
        return null;
    }

    public void delete(String id) {
        synchronized (lock) {
            for (int i = 0; i < conversations.size(); i++) {
                if (conversations.get(i).id.equals(id)) {
                    conversations.remove(i);
                    break;
                }
            }
        }
        save();
    }

    /** 清空全部会话（设置页「清空全部会话」用） */
    public void deleteAll() {
        synchronized (lock) {
            conversations.clear();
        }
        save();
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return conversations.isEmpty();
        }
    }

    public void save() {
        final String data;
        synchronized (lock) {
            JSONArray arr = new JSONArray();
            for (Conversation c : conversations) arr.put(c.toJson());
            data = arr.toString();
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                writeAtomic(data);
            }
        });
    }

    private void writeAtomic(String data) {
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(data);
            w.flush();
        } catch (Exception e) {
            Log.w(TAG, "写入临时文件失败", e);
            return;
        }
        if (!tmp.renameTo(file)) {
            // 某些情况下 rename 失败，回退为直接覆盖
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(data);
            } catch (Exception e) {
                Log.w(TAG, "写入会话文件失败", e);
            }
        }
    }
}
