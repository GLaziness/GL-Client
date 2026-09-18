package mindustry.client.ui;

import arc.Core;

import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import mindustry.client.antigrief.TileRecords;
import mindustry.gen.Tex;

public class HistoryInfoFragment extends Table{

        public HistoryInfoFragment() {
        setBackground(Tex.wavepane);
        Image img = new Image();
        add(img);
        Label label = new Label("");
        add(label).height(126);
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
            label.setText(builder.length() == 0 ? "" : builder.substring(0, builder.length() - 1));
        });
    }
}