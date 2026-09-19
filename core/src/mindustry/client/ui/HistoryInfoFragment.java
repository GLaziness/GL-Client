package mindustry.client.ui;

import arc.Core;
import arc.scene.ui.layout.Scl;

import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import mindustry.client.antigrief.TileRecords;
import mindustry.gen.Tex;

public class HistoryInfoFragment extends Table{

        public HistoryInfoFragment() {
        setBackground(Tex.wavepane);
        Label label = new Label("");
        // the panel only grows to fit the longest line and never shrinks back, so it does not jump with every new line
        var cell = add(label).height(126).minWidth(400f);
        visible(() -> Core.settings.getBool("tilehud"));
        var builder = new StringBuilder();
        update(() -> {
            var record  = TileRecords.INSTANCE.getHistory();
            if (record.size() < 1 ) return;
            builder.setLength(0);
            for (var item : record) {
                item = item.replace(Core.bundle.get("client.built"),"[#41e89a]"+Core.bundle.get("client.built")+"[]").replace(Core.bundle.get("client.broke"),"[#f25c5c]"+Core.bundle.get("client.broke")+"[]");
                builder.append(item).append("\n");
            }
            String text = builder.length() == 0 ? "" : builder.substring(0, builder.length() - 1);
            if(label.textEquals(text)) return;
            label.setText(text);
            float width = label.getPrefWidth();
            if(width > cell.minWidth()){
                cell.minWidth(width / Scl.scl(1f)); // cell sizes are given unscaled
                invalidateHierarchy();
            }
        });
    }
}