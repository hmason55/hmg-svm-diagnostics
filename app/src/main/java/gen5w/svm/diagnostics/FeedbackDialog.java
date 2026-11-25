package gen5w.svm.diagnostics;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

final class FeedbackDialog extends Dialog {
    private final List<FeedbackPages.Page> pages;
    private final String caption;
    private int index;
    private TextView heading;
    private TextView body;
    private ScrollView scroll;
    private Button previous;
    private Button next;

    FeedbackDialog(Context context, List<FeedbackPages.Page> pages, String caption) {
        super(context, android.R.style.Theme_Holo_NoActionBar_Fullscreen);
        this.pages = pages;
        this.caption = caption;
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xff101820);
        int padding = dp(20);
        panel.setPadding(padding, dp(12), padding, dp(8));
        setContentView(panel);

        heading = label(26, Color.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(heading);
        TextView identifier = label(16, 0xffbacad8);
        identifier.setText(caption);
        panel.addView(identifier);

        scroll = new ScrollView(getContext());
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        body = label(22, Color.WHITE);
        body.setPadding(0, dp(10), 0, dp(6));
        body.setLineSpacing(dp(3), 1f);
        scroll.addView(body);

        LinearLayout controls = new LinearLayout(getContext());
        panel.addView(controls);
        previous = button(controls, "Previous", new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(index - 1); }
        });
        next = button(controls, "Next", new View.OnClickListener() {
            @Override public void onClick(View view) { showPage(index + 1); }
        });
        button(controls, "Close", new View.OnClickListener() {
            @Override public void onClick(View view) { dismiss(); }
        });
        showPage(0);
    }

    private TextView label(int size, int color) {
        TextView text = new TextView(getContext());
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private Button button(LinearLayout row, String name, View.OnClickListener listener) {
        Button button = new Button(getContext());
        button.setText(name);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private void showPage(int position) {
        if (position < 0 || position >= pages.size()) return;
        index = position;
        FeedbackPages.Page page = pages.get(index);
        heading.setText((index + 1) + "/" + pages.size() + "  " + page.title);
        body.setText(page.body);
        scroll.scrollTo(0, 0);
        previous.setEnabled(index > 0);
        next.setEnabled(index + 1 < pages.size());
    }

    private int dp(int units) {
        return Math.round(units * getContext().getResources().getDisplayMetrics().density);
    }
}
