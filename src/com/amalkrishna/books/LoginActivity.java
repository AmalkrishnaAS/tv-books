package com.amalkrishna.books;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Sign in: Audiobookshelf servers found on the network on the left (pick one to fill in the
// address), and the server, username and password on the right. The remote's OK opens the TV's
// keyboard in a field. API keys from connect.sh still work and skip this screen.
public class LoginActivity extends Activity implements Discover.Found {
    private static final int FORM_X = 1000, LIST_Y = 380;

    private EditText server, username, password;
    private TextView searching, error;
    private LinearLayout found;
    private Ui.Pill signIn, searchAgain;
    private Discover discover;
    private final List<String> servers = new ArrayList<>();
    private boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        new Backdrop(this, root, null, GradientDrawable.Orientation.TOP_BOTTOM, 0f);

        TextView kicker = Ui.line(this, Ui.px(26), 0x80FFFFFF, Ui.medium);
        kicker.setLetterSpacing(0.08f);
        kicker.setText("BOOKS");
        root.addView(kicker, Ui.at(Ui.px(Ui.MARGIN), Ui.px(92), -2, -2));
        TextView title = Ui.line(this, Ui.px(64), Color.WHITE, Ui.medium);
        title.setText("Sign in to Audiobookshelf");
        root.addView(title, Ui.at(Ui.px(Ui.MARGIN), Ui.px(132), Ui.px(1920 - 2 * Ui.MARGIN), -2));
        TextView intro = Ui.text(this, Ui.px(28), 0xA6FFFFFF, Ui.regular);
        intro.setText("Pick your server, or type its address. Then sign in with your Audiobookshelf account.");
        root.addView(intro, Ui.at(Ui.px(Ui.MARGIN), Ui.px(232), Ui.px(1920 - 2 * Ui.MARGIN), -2));

        // Left: servers on the network.
        int listW = Ui.px(FORM_X - Ui.MARGIN - 80);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        left.addView(heading("ON YOUR NETWORK"));
        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        found.setClipChildren(false);
        left.addView(found, new LinearLayout.LayoutParams(-1, -2));
        searching = Ui.text(this, Ui.px(26), 0x99FFFFFF, Ui.regular);
        left.addView(searching, topMargin(-1, Ui.px(20)));
        searchAgain = new Ui.Pill(this, LineIcon.SERVER, "Search again");
        searchAgain.view.setOnClickListener(v -> search());
        searchAgain.view.setVisibility(View.GONE);
        left.addView(searchAgain.view, topMargin(-2, Ui.px(24), Ui.px(Ui.PILL_H)));
        root.addView(left, Ui.at(Ui.px(Ui.MARGIN), Ui.px(LIST_Y), listW, -2));

        // Right: the form.
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setClipChildren(false);
        server = field("Server", "e.g. 192.168.1.20:13378", InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT);
        username = field("Username", "", InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, EditorInfo.IME_ACTION_NEXT);
        password = field("Password", "", InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        for (EditText f : new EditText[] {server, username, password}) {
            form.addView(heading(String.valueOf(f.getTag())), topMargin(-2, form.getChildCount() > 0 ? Ui.px(24) : 0));
            form.addView(f, new LinearLayout.LayoutParams(-1, Ui.px(84)));
        }
        password.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                signIn();
                return true;
            }
            return false;
        });
        signIn = new Ui.Pill(this, LineIcon.USER, "Sign in");
        signIn.view.setOnClickListener(v -> signIn());
        form.addView(signIn.view, topMargin(-2, Ui.px(36), Ui.px(Ui.PILL_H)));
        error = Ui.text(this, Ui.px(26), 0xFFFF8A80, Ui.regular);
        form.addView(error, topMargin(-1, Ui.px(24)));
        root.addView(form, Ui.at(Ui.px(FORM_X), Ui.px(LIST_Y), Ui.px(1920 - FORM_X - Ui.MARGIN), -2));

        TextView apiKey = Ui.text(this, Ui.px(22), 0x66FFFFFF, Ui.regular);
        apiKey.setText("Or connect with an API key from a computer (connect.sh)");
        root.addView(apiKey, Ui.at(Ui.px(Ui.MARGIN), Ui.px(1000), -2, -2));

        setContentView(root);
        String saved = Abs.server(this);
        server.setText(saved.replaceFirst("^http://", ""));
        username.setText(Abs.user(this));
        (saved.isEmpty() ? server : username.length() == 0 ? username : password).requestFocus();
        search();
    }

    @Override
    protected void onDestroy() {
        if (discover != null) discover.cancel();
        super.onDestroy();
    }

    private TextView heading(String text) {
        TextView t = Ui.line(this, Ui.px(24), 0x80FFFFFF, Ui.medium);
        t.setLetterSpacing(0.08f);
        t.setText(text.toUpperCase(java.util.Locale.ROOT));
        t.setPadding(0, 0, 0, Ui.px(12));
        return t;
    }

    private static LinearLayout.LayoutParams topMargin(int width, int top) {
        return topMargin(width, top, -2);
    }

    private static LinearLayout.LayoutParams topMargin(int width, int top, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.topMargin = top;
        return lp;
    }

    private EditText field(String label, String hint, int variation, int imeAction) {
        EditText f = Ui.field(this, hint, variation, imeAction);
        f.setTag(label);
        return f;
    }

    private void search() {
        if (discover != null) discover.cancel();
        servers.clear();
        found.removeAllViews();
        searching.setText("Searching your network…");
        searchAgain.view.setVisibility(View.GONE);
        discover = Discover.start(this);
    }

    @Override
    public void found(final String url, String version) {
        if (servers.contains(url)) return;
        servers.add(url);
        String address = url.replaceFirst("^http://", "");
        Ui.Pill pill = new Ui.Pill(this, LineIcon.SERVER,
                version.isEmpty() ? address : address + "   ·   Audiobookshelf " + version);
        pill.view.setOnClickListener(v -> {
            server.setText(address);
            (username.length() == 0 ? username : password).requestFocus();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.px(Ui.PILL_H));
        if (found.getChildCount() > 0) lp.topMargin = Ui.px(16);
        found.addView(pill.view, lp);
        // The first server found fills an empty address, so most people only type their login.
        if (server.length() == 0) {
            server.setText(address);
            if (server.isFocused()) username.requestFocus();
        }
    }

    @Override
    public void done() {
        searching.setText(servers.isEmpty() ? "No Audiobookshelf servers found. Type the address instead."
                : servers.size() == 1 ? "Found 1 server" : "Found " + servers.size() + " servers");
        searchAgain.view.setVisibility(View.VISIBLE);
    }

    private void signIn() {
        if (busy) return;
        final String address = Abs.normalize(server.getText().toString());
        final String user = username.getText().toString().trim();
        final String pass = password.getText().toString();
        if (address.isEmpty()) { show(server, "Enter the server's address"); return; }
        if (user.isEmpty()) { show(username, "Enter your username"); return; }
        busy = true;
        error.setText("");
        signIn.set(LineIcon.USER, "Signing in…");
        Abs.run(() -> {
            JSONObject status = Abs.status(address, 5000);
            JSONArray methods = status.optJSONArray("authMethods");
            boolean local = methods == null;
            for (int i = 0; methods != null && i < methods.length(); i++) local |= "local".equals(methods.optString(i));
            if (!local) throw new IOException("This server only allows single sign-on. Use an API key from a computer instead.");
            Abs.login(this, address, user, pass);
            return null;
        }, (json, err) -> {
            busy = false;
            signIn.set(LineIcon.USER, "Sign in");
            if (err != null) {
                show(err.startsWith("Wrong") ? password : signIn.view, err);
                return;
            }
            // A book from another server or account can't carry on.
            if (PlayerService.instance != null) PlayerService.instance.stop();
            setResult(RESULT_OK);
            finish();
        });
    }

    private void show(View focus, String message) {
        error.setText(message);
        focus.requestFocus();
    }
}
