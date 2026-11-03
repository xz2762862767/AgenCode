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
    /** 流式生成中的半成品，独立于会话文件存，避免高频保存把会话文件写得千疮百孔 */
    private static final String DRAFT_NAME = "draft.json";
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
                int bad = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    try {
                        conversations.add(Conversation.fromJson(o));
                    } catch (Exception one) {
                        // 一条坏了就跳过，不能让一条烂数据带走整段历史
                        bad++;
                    }
                }
                sortLocked();
                if (bad > 0) Log.w(TAG, "跳过 " + bad + " 条损坏的会话");
            }
            loadDraft();
        } catch (Exception e) {
            // 整个文件坏了（多半是写盘过程中进程被杀，文件只剩半截）。
            // 必须先备份再继续，否则下一次保存会把它覆盖掉，用户历史就永久没了。
            Log.w(TAG, "解析会话失败，已备份损坏文件", e);
            backupCorrupt();
        }
    }

    /** 把无法解析的存档改名保留，留给用户/后续版本抢救 */
    private void backupCorrupt() {
        try {
            File bad = new File(file.getParentFile(), FILE_NAME + ".bad");
            if (bad.exists() && !bad.delete()) return;
            if (!file.renameTo(bad)) {
                Log.w(TAG, "损坏存档改名失败");
            }
        } catch (Exception ignored) {
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

    /**
     * 流式生成中把已收到的内容落到盘上。
     *
     * <p>切后台久了被系统回收是很常见的事，以前那样只在收尾时入库，
     * 回来时整段回答就凭空消失了；现在至少能接回半成品。</p>
     */
    public void saveDraft(String convId, String text) {
        if (convId == null || text == null || text.isEmpty()) return;
        final String payload;
        try {
            JSONObject o = new JSONObject();
            o.put("convId", convId);
            o.put("text", text);
            payload = o.toString();
        } catch (Exception e) {
            return;
        }
        io.execute(() -> {
            File tmp = new File(file.getParentFile(), DRAFT_NAME + ".tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(payload);
                w.flush();
            } catch (Exception e) {
                Log.w(TAG, "写草稿失败", e);
                return;
            }
            if (!tmp.renameTo(draftFile())) tmp.delete();
        });
    }

    /** 一轮正常收尾：草稿使命结束 */
    public void clearDraft() {
        io.execute(() -> {
            File f = draftFile();
            if (f.exists() && !f.delete()) Log.w(TAG, "清理草稿失败");
        });
    }

    private File draftFile() {
        return new File(file.getParentFile(), DRAFT_NAME);
    }

    /** 启动时把上次没写完的半截回答接回它所属的会话 */
    private void loadDraft() {
        File f = draftFile();
        if (!f.exists()) return;
        String raw = readAll(f);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            final String convId = o.optString("convId");
            String text = o.optString("text");
            if (text == null || text.trim().isEmpty()) return;
            synchronized (lock) {
                for (Conversation c : conversations) {
                    if (c.id != null && c.id.equals(convId)) {
                        Message m = new Message(Message.ROLE_ASSISTANT, text
                                + "\n\n⚠️ 这条回答写到一半 App 被系统回收了，上面是已经收到的部分。"
                                + "要完整答案的话，把问题再发一次。");
                        m.error = true;
                        c.messages.add(m);
                        c.touch();
                        break;
                    }
                }
                sortLocked();
            }
        } catch (Exception e) {
            Log.w(TAG, "读取草稿失败", e);
        }
    }

    private static String readAll(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } catch (Exception e) {
            return null;
        }
        return sb.toString();
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
