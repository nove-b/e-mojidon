package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import org.joinmastodon.android.ui.utils.UiUtils;

/**
 * A horizontally swipeable, page-sized container for post media attachments.
 */
public class MediaSliderLayout extends HorizontalScrollView{
	private final LinearLayout pages;
	private float aspectRatio=1f;
	private OnPageChangedListener pageChangedListener;

	public MediaSliderLayout(Context context){
		super(context);
		setHorizontalScrollBarEnabled(false);
		setFillViewport(true);
		pages=new LinearLayout(context);
		pages.setOrientation(LinearLayout.HORIZONTAL);
		addView(pages, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
	}

	public void setAspectRatio(float aspectRatio){
		this.aspectRatio=Math.max(0.6f, Math.min(1.8f, aspectRatio));
		requestLayout();
	}

	public void addSlide(View view){
		pages.addView(view);
	}

	public void clearSlides(){
		pages.removeAllViews();
	}

	public void setOnPageChangedListener(OnPageChangedListener listener){
		pageChangedListener=listener;
	}

	public void scrollToPage(int page){
		smoothScrollTo(Math.max(0, Math.min(page, pages.getChildCount()-1))*getWidth(), 0);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event){
		boolean handled=super.onTouchEvent(event);
		if(event.getActionMasked()==MotionEvent.ACTION_UP && getWidth()>0)
			smoothScrollTo(Math.round(getScrollX()/(float) getWidth())*getWidth(), 0);
		return handled;
	}

	@Override
	protected void onScrollChanged(int l, int t, int oldl, int oldt){
		super.onScrollChanged(l, t, oldl, oldt);
		if(pageChangedListener!=null && getWidth()>0)
			pageChangedListener.onPageChanged(Math.round(l/(float) getWidth()));
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
		int width=Math.min(UiUtils.MAX_WIDTH, MeasureSpec.getSize(widthMeasureSpec));
		int height=Math.round(width/aspectRatio);
		int pageCount=pages.getChildCount();
		for(int i=0;i<pageCount;i++){
			View child=pages.getChildAt(i);
			child.setLayoutParams(new LinearLayout.LayoutParams(width, height));
		}
		pages.measure(MeasureSpec.makeMeasureSpec(width*pageCount, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
		setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
	}

	public interface OnPageChangedListener{
		void onPageChanged(int page);
	}
}
