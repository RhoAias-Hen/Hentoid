package me.devsaki.hentoid.adapters;

import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import me.devsaki.hentoid.HentoidApplication;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.holders.ContentHolder;
import me.devsaki.hentoid.util.AndroidHelper;
import me.devsaki.hentoid.util.LogHelper;

/**
 * Created by avluis on 04/23/2016.
 * RecyclerView based Content Adapter
 */
public class ContentAdapter extends RecyclerView.Adapter<ContentHolder> {
    private static final String TAG = LogHelper.makeLogTag(ContentAdapter.class);

    private final Context cxt;
    private List<Content> contents = new ArrayList<>();
    private int focusedItem = -1;

    public ContentAdapter(Context cxt, final List<Content> contents) {
        this.cxt = cxt;
        this.contents = contents;
    }

    public void setContentList(List<Content> contentList) {
        this.contents = contentList;
        updateContentList();
    }

    private void updateContentList() {
        focusedItem = -1;
        this.notifyDataSetChanged();
    }

    @Override
    public ContentHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        final View view = LayoutInflater.from(parent.getContext()).inflate(
                R.layout.row_downloads, parent, false);

        return new ContentHolder(view);
    }

    @Override
    public void onBindViewHolder(final ContentHolder holder, final int position) {
        final Content content = contents.get(position);

        if (focusedItem != -1) {
            holder.itemView.setSelected(focusedItem == position);
        }

        if (holder.itemView.isSelected()) {
            LogHelper.d(TAG, "Position: " + position + ": " + content.getTitle()
                    + " is a selected item currently in view.");
        }

        String templateTvSeries = cxt.getResources().getString(R.string.tvSeries);
        String templateTvArtist = cxt.getResources().getString(R.string.tvArtists);
        String templateTvTags = cxt.getResources().getString(R.string.tvTags);

        if (content.getTitle() == null) {
            holder.tvTitle.setText(R.string.tvEmpty);
        } else {
            holder.tvTitle.setText(content.getTitle());
            holder.tvTitle.setSelected(true);
        }

        File coverFile = AndroidHelper.getThumb(content, cxt);
        String image = coverFile != null ?
                coverFile.getAbsolutePath() : content.getCoverImageUrl();

        HentoidApplication.getInstance().loadBitmap(image, holder.ivCover);

        String series = "";
        List<Attribute> seriesAttributes = content.getAttributes().get(AttributeType.SERIE);
        if (seriesAttributes == null) {
            holder.tvSeries.setVisibility(View.GONE);
        } else {
            for (int i = 0; i < seriesAttributes.size(); i++) {
                Attribute attribute = seriesAttributes.get(i);
                series += attribute.getName();
                if (i != seriesAttributes.size() - 1) {
                    series += ", ";
                }
            }
            holder.tvSeries.setVisibility(View.VISIBLE);
        }
        holder.tvSeries.setText(Html.fromHtml(templateTvSeries.replace("@series@", series)));

        String artists = "";
        List<Attribute> artistAttributes = content.getAttributes().get(AttributeType.ARTIST);
        if (artistAttributes == null) {
            holder.tvArtist.setVisibility(View.GONE);
        } else {
            for (int i = 0; i < artistAttributes.size(); i++) {
                Attribute attribute = artistAttributes.get(i);
                artists += attribute.getName();
                if (i != artistAttributes.size() - 1) {
                    artists += ", ";
                }
            }
            holder.tvArtist.setVisibility(View.VISIBLE);
        }
        holder.tvArtist.setText(Html.fromHtml(templateTvArtist.replace("@artist@", artists)));

        if (seriesAttributes == null && artistAttributes == null) {
            holder.tvSeries.setText(R.string.tvEmpty);
            holder.tvSeries.setVisibility(View.VISIBLE);
        }

        String tags = "";
        List<Attribute> tagsAttributes = content.getAttributes().get(AttributeType.TAG);
        if (tagsAttributes != null) {
            for (int i = 0; i < tagsAttributes.size(); i++) {
                Attribute attribute = tagsAttributes.get(i);
                if (attribute.getName() != null) {
                    tags += templateTvTags.replace("@tag@", attribute.getName());
                    if (i != tagsAttributes.size() - 1) {
                        tags += ", ";
                    }
                }
            }
        }
        holder.tvTags.setText(Html.fromHtml(tags));

        if (content.getSite() != null) {
            int img = content.getSite().getIco();
            holder.ivSite.setImageResource(img);
            holder.ivSite.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AndroidHelper.viewContent(content, cxt);
                }
            });
        } else {
            holder.ivSite.setImageResource(R.drawable.ic_stat_hentoid);
        }

        if (content.getStatus() != null) {
            String status = content.getStatus().getDescription();
            int bg;
            switch (status) {
                case "Downloaded":
                    bg = R.color.card_item_src_normal;
                    break;
                case "Migrated":
                    bg = R.color.card_item_src_migrated;
                    break;
                default:
                    LogHelper.d(TAG, status);
                    bg = R.color.card_item_src_other;
            }
            holder.ivSite.setBackgroundColor(ContextCompat.getColor(cxt, bg));
        } else {
            holder.ivSite.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (focusedItem != -1) {
                    notifyItemChanged(focusedItem);
                    focusedItem = -1;
                    notifyItemChanged(focusedItem);
                }

                AndroidHelper.toast(cxt, "Opening: " + content.getTitle());
                AndroidHelper.openContent(content, cxt);
            }
        });

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                // If focusItem is set, ignore
                if (focusedItem != -1) {
                    // If focusItem is the same as set, unset
                    if (holder.getLayoutPosition() == focusedItem) {
                        notifyItemChanged(focusedItem);
                        holder.itemView.setSelected(false);
                        notifyItemChanged(focusedItem);
                        focusedItem = -1;
                        LogHelper.d(TAG, "Position: " + holder.getLayoutPosition()
                                + ": " + content.getTitle() + " has been unselected.");
                    }
                    return true;
                }

                // If focusItem is not set, set
                notifyItemChanged(focusedItem);
                focusedItem = holder.getLayoutPosition();
                notifyItemChanged(focusedItem);

                AndroidHelper.toast(cxt, "Not yet implemented.");
                LogHelper.d(TAG, "Position: " + holder.getAdapterPosition()
                        + ": " + content.getTitle() + " has been selected.");

                return true;
            }
        });
    }

    public void add(int position, Content item) {
        contents.add(position, item);
        notifyItemInserted(position);
    }

    @Override
    public long getItemId(int position) {
        return contents.get(position).getId();
    }

    @Override
    public int getItemCount() {
        return (null != contents ? contents.size() : 0);
    }

    // TODO: Remove item from db and file system
    public void remove(Content item) {
        int position = contents.indexOf(item);
        LogHelper.d(TAG, "Removing item: " + item.getTitle() + " from adapter" +
                // ", db and file system" +
                ".");
        contents.remove(position);
        notifyItemRemoved(position);
    }
}