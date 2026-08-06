package org.joinmastodon.android.ui.displayitems;

import static org.joinmastodon.android.GlobalUserPreferences.*;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.fragments.BaseStatusListFragment;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Translation;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.PhotoLayoutHelper;
import org.joinmastodon.android.ui.drawables.SpoilerStripesDrawable;
import org.joinmastodon.android.ui.photoviewer.AltTextSheet;
import org.joinmastodon.android.ui.photoviewer.PhotoViewerHost;
import org.joinmastodon.android.ui.utils.MediaAttachmentViewController;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.FrameLayoutThatOnlyMeasuresFirstChild;
import org.joinmastodon.android.ui.views.MaxWidthFrameLayout;
import org.joinmastodon.android.ui.views.MediaSliderLayout;
import org.joinmastodon.android.utils.TypedObjectPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;

public class MediaGridStatusDisplayItem extends StatusDisplayItem{
	private static final String TAG="MediaGridDisplayItem";

	private PhotoLayoutHelper.TiledLayoutResult tiledLayout;
	private final TypedObjectPool<GridItemType, MediaAttachmentViewController> viewPool;
	private final List<Attachment> attachments;
	private final Map<String, Pair<String, String>> translatedAttachments = new HashMap<>();
	private final ArrayList<ImageLoaderRequest> requests=new ArrayList<>();
	public String sensitiveTitle;

	public MediaGridStatusDisplayItem(String parentID, BaseStatusListFragment<?> parentFragment, PhotoLayoutHelper.TiledLayoutResult tiledLayout, List<Attachment> attachments, Status status){
		super(parentID, parentFragment);
		this.tiledLayout=tiledLayout;
		this.viewPool=parentFragment.getAttachmentViewsPool();
		this.attachments=attachments;
		this.status=status;
		for(Attachment att:attachments){
			requests.add(new UrlImageLoaderRequest(switch(att.type){
				case IMAGE -> att.url;
				case VIDEO, GIFV -> att.previewUrl == null ? att.url : att.previewUrl;
				default -> throw new IllegalStateException("Unexpected value: "+att.url);
			}, 1000, 1000));
		}
	}

	@Override
	public Type getType(){
		return Type.MEDIA_GRID;
	}

	@Override
	public int getImageCount(){
		return requests.size();
	}

	@Override
	public ImageLoaderRequest getImageRequest(int index){
		return requests.get(index);
	}

	public enum GridItemType{
		PHOTO,
		VIDEO,
		GIFV
	}

	public static class Holder extends StatusDisplayItem.Holder<MediaGridStatusDisplayItem> implements ImageLoaderViewHolder{
		private final FrameLayout wrapper;
		private final MediaSliderLayout layout;
		private final View.OnClickListener clickListener=this::onViewClick, altTextClickListener=this::onAltTextClick;
		private final ArrayList<MediaAttachmentViewController> controllers=new ArrayList<>();

		private final MaxWidthFrameLayout overlays;
		private final FrameLayout altTextWrapper;
		private final TextView altTextButton;
		private final ImageView noAltTextButton;
		private final LinearLayout pageIndicator;
		private final ImageButton previousPage, nextPage;
		private final ArrayList<View> pageDots=new ArrayList<>();
		private final View altTextScroller;
		private final ImageButton altTextClose;
		private final TextView altText, noAltText;

		private final View sensitiveOverlay;
		private final LayerDrawable sensitiveOverlayBG;
		private static final ColorDrawable drawableForWhenThereIsNoBlurhash=new ColorDrawable(0xffffffff);
//		private final FrameLayout hideSensitiveButton;
		private final TextView sensitiveText;

		private int altTextIndex=-1;
		private Animator altTextAnimator;

		public Holder(Activity activity, ViewGroup parent){
			super(new FrameLayoutThatOnlyMeasuresFirstChild(activity));
			wrapper=(FrameLayout)itemView;
			layout=new MediaSliderLayout(activity);
			wrapper.addView(layout);
			wrapper.setClipToPadding(false);

			overlays=new MaxWidthFrameLayout(activity);
			overlays.setMaxWidth(UiUtils.MAX_WIDTH);
			wrapper.addView(overlays, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));

			activity.getLayoutInflater().inflate(R.layout.overlay_image_alt_text, overlays);
			altTextWrapper=findViewById(R.id.alt_text_wrapper);
			altTextButton=findViewById(R.id.alt_button);
			noAltTextButton=findViewById(R.id.no_alt_button);
			altTextScroller=findViewById(R.id.alt_text_scroller);
			altTextClose=findViewById(R.id.alt_text_close);
			altText=findViewById(R.id.alt_text);
			noAltText=findViewById(R.id.no_alt_text);
			altTextClose.setOnClickListener(this::onAltTextCloseClick);
			pageIndicator=new LinearLayout(activity);
			pageIndicator.setGravity(Gravity.CENTER);
			pageIndicator.setPadding(V.dp(8), V.dp(6), V.dp(8), V.dp(6));
			overlays.addView(pageIndicator, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL|Gravity.BOTTOM));
			previousPage=createPageButton(activity, false);
			nextPage=createPageButton(activity, true);
			overlays.addView(previousPage, new FrameLayout.LayoutParams(V.dp(40), V.dp(40), Gravity.START|Gravity.CENTER_VERTICAL));
			overlays.addView(nextPage, new FrameLayout.LayoutParams(V.dp(40), V.dp(40), Gravity.END|Gravity.CENTER_VERTICAL));
			previousPage.setOnClickListener(v->layout.scrollToPage(layout.getScrollX()/layout.getWidth()-1));
			nextPage.setOnClickListener(v->layout.scrollToPage(layout.getScrollX()/layout.getWidth()+1));
			layout.setOnPageChangedListener(page->updatePageIndicator(page));

			// megalodon: no sensitive hide button because the visibility toggle looks prettier imo
//			hideSensitiveButton=(FrameLayout) activity.getLayoutInflater().inflate(R.layout.alt_text_badge, overlays, false);
//			((TextView) hideSensitiveButton.findViewById(R.id.alt_button)).setText(R.string.hide);
//			overlays.addView(hideSensitiveButton, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));

			activity.getLayoutInflater().inflate(R.layout.overlay_image_sensitive, overlays);
			sensitiveOverlay=findViewById(R.id.sensitive_overlay);
			sensitiveOverlayBG=(LayerDrawable) sensitiveOverlay.getBackground().mutate();
			sensitiveOverlayBG.setDrawableByLayerId(R.id.left_drawable, new SpoilerStripesDrawable(false));
			sensitiveOverlayBG.setDrawableByLayerId(R.id.right_drawable, new SpoilerStripesDrawable(true));
			sensitiveOverlay.setBackground(sensitiveOverlayBG);
			sensitiveOverlay.setOnClickListener(v->revealSensitive());
//			hideSensitiveButton.setOnClickListener(v->hideSensitive());

			sensitiveText=findViewById(R.id.sensitive_text);
		}

		@Override
		public void onBind(MediaGridStatusDisplayItem item){
			wrapper.setPadding(0, 0, 0, 0); // item.inset ? 0 : V.dp(8));

			if(altTextAnimator!=null)
				altTextAnimator.cancel();

			Attachment firstAttachment=item.attachments.get(0);
			layout.setAspectRatio(firstAttachment.getWidth()/(float) firstAttachment.getHeight());
			for(MediaAttachmentViewController c:controllers){
				item.viewPool.reuse(c.type, c);
			}
			layout.clearSlides();
			layout.scrollTo(0, 0);
			controllers.clear();

			int i=0;
			if (!item.attachments.isEmpty()) updateBlurhashInSensitiveOverlay();
			for(Attachment att:item.attachments){
				MediaAttachmentViewController c=item.viewPool.obtain(switch(att.type){
					case IMAGE -> GridItemType.PHOTO;
					case VIDEO -> GridItemType.VIDEO;
					case GIFV -> GridItemType.GIFV;
					default -> throw new IllegalStateException("Unexpected value: "+att.type);
				});
				layout.addSlide(c.view);
				c.view.setOnClickListener(clickListener);
				c.view.setTag(i);
				if(c.btnsWrap!=null){
					c.btnsWrap.setOnClickListener(altTextClickListener);
					c.btnsWrap.setTag(i);
					c.btnsWrap.setAlpha(1f);
				}
				controllers.add(c);

				if (item.status.translation != null){
					if(item.status.translationState==Status.TranslationState.SHOWN){
						if(!item.translatedAttachments.containsKey(att.id)){
							Optional<Translation.MediaAttachment> translatedAttachment=Arrays.stream(item.status.translation.mediaAttachments).filter(mediaAttachment->mediaAttachment.id.equals(att.id)).findFirst();
							translatedAttachment.ifPresent(mediaAttachment->{
								item.translatedAttachments.put(mediaAttachment.id, new Pair<>(att.description, mediaAttachment.description));
								att.description=mediaAttachment.description;
							});
						}else{
							//SAFETY: must be non-null, as we check if the map contains the attachment before
							att.description=Objects.requireNonNull(item.translatedAttachments.get(att.id)).second;
						}
					}else{
						if (item.translatedAttachments.containsKey(att.id)) {
							att.description=Objects.requireNonNull(item.translatedAttachments.get(att.id)).first;
						}
					}
				}
				c.bind(att, item.status);
				i++;
			}
			altTextButton.setVisibility(View.VISIBLE);
			noAltTextButton.setVisibility(View.VISIBLE);
			altTextWrapper.setVisibility(View.GONE);
			altTextIndex=-1;
			pageIndicator.setVisibility(item.attachments.size()>1 ? View.VISIBLE : View.GONE);
			previousPage.setVisibility(item.attachments.size()>1 ? View.VISIBLE : View.GONE);
			nextPage.setVisibility(item.attachments.size()>1 ? View.VISIBLE : View.GONE);
			buildPageIndicator();
			updatePageIndicator(0);

			if(!item.status.sensitiveRevealed){
				sensitiveOverlay.setVisibility(View.VISIBLE);
				layout.setVisibility(View.INVISIBLE);
			}else{
				sensitiveOverlay.setVisibility(View.GONE);
				layout.setVisibility(View.VISIBLE);
			}
//			hideSensitiveButton.setVisibility(item.status.sensitive ? View.VISIBLE : View.GONE);
			if(!TextUtils.isEmpty(item.sensitiveTitle))
				sensitiveText.setText(item.sensitiveTitle);
			else if (!item.status.sensitive)
				sensitiveText.setText(R.string.media_hidden);
			else
				sensitiveText.setText(R.string.sensitive_content_explain);

			boolean insetAndLast=item.inset && isLastDisplayItemForStatus();
			wrapper.setClipToOutline(insetAndLast);
			wrapper.setOutlineProvider(insetAndLast ? OutlineProviders.bottomRoundedRect(12) : null);
		}

		@Override
		public void setImage(int index, Drawable drawable){
			if(item.attachments.get(index).meta==null){
				Rect bounds=drawable.getBounds();
				drawable.setBounds(bounds.left, bounds.top, bounds.left+drawable.getIntrinsicWidth(), bounds.top+drawable.getIntrinsicHeight());
				Attachment.Metadata metadata = new Attachment.Metadata();
				metadata.width=drawable.getIntrinsicWidth();
				metadata.height=drawable.getIntrinsicHeight();
				item.attachments.get(index).meta=metadata;
				Attachment firstAttachment=item.attachments.get(0);
				layout.setAspectRatio(firstAttachment.getWidth()/(float) firstAttachment.getHeight());
				UiUtils.beginLayoutTransition((ViewGroup) itemView);
				rebind();
			}
			controllers.get(index).setImage(drawable);
		}

		@Override
		public void clearImage(int index){
			controllers.get(index).clearImage();
		}

		private void onViewClick(View v){
			int index=(Integer)v.getTag();
			((PhotoViewerHost) item.parentFragment).openPhotoViewer(item.parentID, item.status, index, this);
		}

		private void updatePageIndicator(int page){
			for(int i=0;i<pageDots.size();i++){
				boolean selected=i==page;
				View dot=pageDots.get(i);
				LinearLayout.LayoutParams params=(LinearLayout.LayoutParams) dot.getLayoutParams();
				params.width=V.dp(selected ? 16 : 6);
				dot.setLayoutParams(params);
				GradientDrawable background=(GradientDrawable) dot.getBackground();
				background.setColor(selected ? 0xFFFFFFFF : 0x99FFFFFF);
			}
			previousPage.setAlpha(page>0 ? 1f : 0.35f);
			nextPage.setAlpha(item!=null && page<item.attachments.size()-1 ? 1f : 0.35f);
		}

		private ImageButton createPageButton(Activity activity, boolean next){
			ImageButton button=new ImageButton(activity);
			button.setImageResource(R.drawable.ic_fluent_arrow_left_24_regular);
			button.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
			if(next) button.setRotation(180);
			GradientDrawable background=new GradientDrawable();
			background.setShape(GradientDrawable.OVAL);
			background.setColor(0x66000000);
			button.setBackground(background);
			button.setContentDescription(activity.getString(next ? R.string.next : R.string.back));
			return button;
		}

		private void buildPageIndicator(){
			pageIndicator.removeAllViews();
			pageDots.clear();
			for(int i=0;i<item.attachments.size();i++){
				View dot=new View(pageIndicator.getContext());
				GradientDrawable background=new GradientDrawable();
				background.setShape(GradientDrawable.RECTANGLE);
				background.setCornerRadius(V.dp(3));
				dot.setBackground(background);
				LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(V.dp(6), V.dp(6));
				params.setMargins(V.dp(3), 0, V.dp(3), 0);
				pageIndicator.addView(dot, params);
				pageDots.add(dot);
			}
		}

		private void onAltTextClick(View v){
			if(altTextAnimator!=null)
				altTextAnimator.cancel();
//			V.setVisibilityAnimated(hideSensitiveButton, View.GONE);
			V.cancelVisibilityAnimation(altTextWrapper);
			v.setVisibility(View.INVISIBLE);
			int index=(Integer)v.getTag();
			altTextIndex=index;
			Attachment att=item.attachments.get(index);
			boolean hasAltText = !TextUtils.isEmpty(att.description);
			if ((hasAltText && !showAltIndicator) || (!hasAltText && !showNoAltIndicator)) return;
			altTextButton.setVisibility(hasAltText && showAltIndicator ? View.VISIBLE : View.GONE);
			noAltTextButton.setVisibility(!hasAltText && showNoAltIndicator ? View.VISIBLE : View.GONE);
			altText.setVisibility(hasAltText && showAltIndicator ? View.VISIBLE : View.GONE);
			noAltText.setVisibility(!hasAltText && showNoAltIndicator ? View.VISIBLE : View.GONE);
			altText.setText(att.description);
			altTextWrapper.setVisibility(View.VISIBLE);
			altTextWrapper.setBackgroundResource(hasAltText ? R.drawable.bg_image_alt_text_overlay : R.drawable.bg_image_no_alt_overlay);
			altTextWrapper.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
				@Override
				public boolean onPreDraw(){
					altTextWrapper.getViewTreeObserver().removeOnPreDrawListener(this);

					int[] loc={0, 0};
					v.getLocationInWindow(loc);
					int btnL=loc[0], btnT=loc[1];
					overlays.getLocationInWindow(loc);
					btnL-=loc[0];
					btnT-=loc[1];

					ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) altTextWrapper.getLayoutParams();
					ArrayList<Animator> anims=new ArrayList<>();
					anims.add(ObjectAnimator.ofFloat(altTextButton, View.ALPHA, 1, 0));
					anims.add(ObjectAnimator.ofFloat(noAltTextButton, View.ALPHA, 1, 0));
					anims.add(ObjectAnimator.ofFloat(altTextScroller, View.ALPHA, 0, 1));
					anims.add(ObjectAnimator.ofFloat(altTextClose, View.ALPHA, 0, 1));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "left", btnL+margins.leftMargin, altTextWrapper.getLeft()));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "top", btnT+margins.topMargin, altTextWrapper.getTop()));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "right", btnL+v.getWidth()-margins.rightMargin, altTextWrapper.getRight()));
					anims.add(ObjectAnimator.ofInt(altTextWrapper, "bottom", btnT+v.getHeight()-margins.bottomMargin, altTextWrapper.getBottom()));
					for(Animator a:anims)
						a.setDuration(300);

					for(MediaAttachmentViewController c:controllers){
						if(c.btnsWrap!=null && c.btnsWrap!=v){
							anims.add(ObjectAnimator.ofFloat(c.btnsWrap, View.ALPHA, 1, 0).setDuration(150));
						}
						if (c.extraBadge != null) {
							anims.add(ObjectAnimator.ofFloat(c.extraBadge, View.ALPHA, 1, 0).setDuration(150));
						}
					}

					AnimatorSet set=new AnimatorSet();
					set.playTogether(anims);
					set.setInterpolator(CubicBezierInterpolator.DEFAULT);
					set.addListener(new AnimatorListenerAdapter(){
						@Override
						public void onAnimationEnd(Animator animation){
							altTextAnimator=null;
							for(MediaAttachmentViewController c:controllers){
								if(c.btnsWrap!=null){
									c.btnsWrap.setVisibility(View.INVISIBLE);
								}
								if (c.extraBadge != null) c.extraBadge.setVisibility(View.INVISIBLE);
							}
						}
					});
					altTextAnimator=set;
					set.start();

					return true;
				}
			});
		}

		private void onAltTextCloseClick(View v){
			if(altTextAnimator!=null)
				altTextAnimator.cancel();

//			V.setVisibilityAnimated(hideSensitiveButton, item.status.sensitive ? View.VISIBLE : View.GONE);
			V.cancelVisibilityAnimation(altTextWrapper);
			View btn=controllers.get(altTextIndex).btnsWrap;
			int i=0;
			for(MediaAttachmentViewController c:controllers){
				boolean hasAltText = !TextUtils.isEmpty(item.attachments.get(i).description);
				if(c.btnsWrap!=null
						&& c.btnsWrap!=btn
						&& ((hasAltText && showAltIndicator) || (!hasAltText && showNoAltIndicator))
				) c.btnsWrap.setVisibility(View.VISIBLE);
				if (c.extraBadge != null) c.extraBadge.setVisibility(View.VISIBLE);
				i++;
			}

			int[] loc={0, 0};
			btn.getLocationInWindow(loc);
			int btnL=loc[0], btnT=loc[1];
			overlays.getLocationInWindow(loc);
			btnL-=loc[0];
			btnT-=loc[1];

			ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) altTextWrapper.getLayoutParams();
			ArrayList<Animator> anims=new ArrayList<>();
			anims.add(ObjectAnimator.ofFloat(altTextButton, View.ALPHA, 1));
			anims.add(ObjectAnimator.ofFloat(noAltTextButton, View.ALPHA, 1));
			anims.add(ObjectAnimator.ofFloat(altTextScroller, View.ALPHA, 0));
			anims.add(ObjectAnimator.ofFloat(altTextClose, View.ALPHA, 0));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "left", btnL+margins.leftMargin));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "top", btnT+margins.topMargin));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "right", btnL+btn.getWidth()-margins.rightMargin));
			anims.add(ObjectAnimator.ofInt(altTextWrapper, "bottom", btnT+btn.getHeight()-margins.bottomMargin));
			for(Animator a:anims)
				a.setDuration(300);

			for(MediaAttachmentViewController c:controllers){
				if(c.btnsWrap!=null && c.btnsWrap!=btn){
					anims.add(ObjectAnimator.ofFloat(c.btnsWrap, View.ALPHA, 1).setDuration(150));
				}
				if (c.extraBadge != null) {
					anims.add(ObjectAnimator.ofFloat(c.extraBadge, View.ALPHA, 1).setDuration(150));
				}
			}

			AnimatorSet set=new AnimatorSet();
			set.playTogether(anims);
			set.setInterpolator(CubicBezierInterpolator.DEFAULT);
			set.addListener(new AnimatorListenerAdapter(){
				@Override
				public void onAnimationEnd(Animator animation){
					altTextAnimator=null;
					V.setVisibilityAnimated(altTextWrapper, View.GONE);
					V.setVisibilityAnimated(btn, View.VISIBLE);
					btn.setAlpha(1);
				}
			});
			altTextAnimator=set;
			set.start();
		}

		public MediaAttachmentViewController getViewController(int index){
			return index<controllers.size() ? controllers.get(index) : null;
		}

		public void setClipChildren(boolean clip){
			layout.setClipChildren(clip);
			wrapper.setClipChildren(clip);
		}

		private void updateBlurhashInSensitiveOverlay(){
			Drawable d = item.attachments.get(0).blurhashPlaceholder;
			sensitiveOverlayBG.setDrawableByLayerId(R.id.blurhash, d==null ? drawableForWhenThereIsNoBlurhash : d.mutate());
			sensitiveOverlay.setBackground(sensitiveOverlayBG);
		}

		public void revealSensitive(){
			if(item.status.sensitiveRevealed)
				return;
			item.status.sensitiveRevealed=true;
			V.setVisibilityAnimated(sensitiveOverlay, View.GONE);
			layout.setVisibility(View.VISIBLE);
			item.parentFragment.onSensitiveRevealed(this);
		}

		public void hideSensitive(){
			if(!item.status.sensitiveRevealed)
				return;
			updateBlurhashInSensitiveOverlay();
			item.status.sensitiveRevealed=false;
			V.setVisibilityAnimated(sensitiveOverlay, View.VISIBLE, ()->layout.setVisibility(View.INVISIBLE));
		}

		public MediaSliderLayout getLayout(){
			return layout;
		}

		public View getSensitiveOverlay(){
			return sensitiveOverlay;
		}
	}
}
