package net.osmand.plus.wikivoyage.explore;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.ColorInt;
import android.support.annotation.ColorRes;
import android.support.annotation.DrawableRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomNavigationView;
import android.support.design.widget.BottomNavigationView.OnNavigationItemSelectedListener;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import net.osmand.AndroidUtils;
import net.osmand.plus.LockableViewPager;
import net.osmand.plus.OnDialogFragmentResultListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.TabActivity;
import net.osmand.plus.download.DownloadIndexesThread.DownloadEvents;
import net.osmand.plus.wikivoyage.article.WikivoyageArticleDialogFragment;
import net.osmand.plus.wikivoyage.data.TravelArticle;
import net.osmand.plus.wikivoyage.data.TravelDbHelper;
import net.osmand.plus.wikivoyage.search.WikivoyageSearchDialogFragment;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class WikivoyageExploreActivity extends TabActivity implements DownloadEvents, OnDialogFragmentResultListener {

	private static final String TAB_SELECTED = "tab_selected";
	private static final String CITY_ID_KEY = "city_id_key";
	private static final String SELECTED_LANG_KEY = "selected_lang_key";

	private static final int EXPLORE_POSITION = 0;
	private static final int SAVED_ARTICLES_POSITION = 1;

	private OsmandApplication app;
	private boolean nightMode;
	protected List<WeakReference<Fragment>> fragments = new ArrayList<>();

	private LockableViewPager viewPager;
	private boolean updateNeeded;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		app = getMyApplication();
		OsmandSettings settings = app.getSettings();
		nightMode = !settings.isLightContent();

		int themeId = nightMode ? R.style.OsmandDarkTheme_NoActionbar : R.style.OsmandLightTheme_NoActionbar_LightStatusBar;
		app.setLanguage(this);
		setTheme(themeId);
		super.onCreate(savedInstanceState);

		setContentView(R.layout.wikivoyage_explore);

		Window window = getWindow();
		if (window != null) {
			if (settings.DO_NOT_USE_ANIMATIONS.get()) {
				window.getAttributes().windowAnimations = R.style.Animations_NoAnimation;
			}
			if (Build.VERSION.SDK_INT >= 21) {
				window.setStatusBarColor(getResolvedColor(getStatusBarColor()));
			}
		}

		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		toolbar.setNavigationIcon(getContentIcon(R.drawable.ic_arrow_back));
		toolbar.setNavigationContentDescription(R.string.access_shared_string_navigate_up);
		toolbar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				finish();
			}
		});

		findViewById(R.id.options_button).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				FragmentManager fm = getSupportFragmentManager();
				if (fm == null) {
					return;
				}
				WikivoyageOptionsBottomSheetDialogFragment fragment = new WikivoyageOptionsBottomSheetDialogFragment();
				fragment.setUsedOnMap(false);
				fragment.show(fm, WikivoyageOptionsBottomSheetDialogFragment.TAG);
			}
		});

		int searchColorId = nightMode ? R.color.icon_color : R.color.ctx_menu_title_color_dark;
		((TextView) findViewById(R.id.search_hint)).setTextColor(getResolvedColor(searchColorId));
		((ImageView) findViewById(R.id.search_icon))
				.setImageDrawable(getIcon(R.drawable.ic_action_search_dark, searchColorId));

		findViewById(R.id.search_button).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				WikivoyageSearchDialogFragment.showInstance(getSupportFragmentManager());
			}
		});

		viewPager = (LockableViewPager) findViewById(R.id.view_pager);
		viewPager.setOffscreenPageLimit(2);
		viewPager.setSwipeLocked(true);
		setViewPagerAdapter(viewPager, new ArrayList<TabItem>());
		OsmandFragmentPagerAdapter pagerAdapter = (OsmandFragmentPagerAdapter) viewPager.getAdapter();
		if (pagerAdapter != null) {
			pagerAdapter.addTab(getTabIndicator(R.string.shared_string_explore, ExploreTabFragment.class));
			pagerAdapter.addTab(getTabIndicator(R.string.saved_articles, SavedArticlesTabFragment.class));
		}

		final ColorStateList navColorStateList = AndroidUtils.createBottomNavColorStateList(app, nightMode);
		final BottomNavigationView bottomNav = (BottomNavigationView) findViewById(R.id.bottom_navigation);
		bottomNav.setItemIconTintList(navColorStateList);
		bottomNav.setItemTextColor(navColorStateList);
		bottomNav.setOnNavigationItemSelectedListener(new OnNavigationItemSelectedListener() {
			@Override
			public boolean onNavigationItemSelected(@NonNull MenuItem item) {
				int position = -1;
				switch (item.getItemId()) {
					case R.id.action_explore:
						position = EXPLORE_POSITION;
						break;
					case R.id.action_saved_articles:
						position = SAVED_ARTICLES_POSITION;
						break;
				}
				if (position != -1 && position != viewPager.getCurrentItem()) {
					viewPager.setCurrentItem(position);
					return true;
				}
				return false;
			}
		});

		updateSearchBarVisibility();
		populateData();
	}

	@Override
	public void onAttachFragment(Fragment fragment) {
		fragments.add(new WeakReference<>(fragment));
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

	}

	@Override
	protected void onResume() {
		super.onResume();
		Intent intent = getIntent();
		if (intent != null) {
			int currentItem = intent.getIntExtra(TAB_SELECTED, 0);
			if (currentItem == SAVED_ARTICLES_POSITION) {
				BottomNavigationView bottomNav = (BottomNavigationView) findViewById(R.id.bottom_navigation);
				bottomNav.setSelectedItemId(R.id.action_saved_articles);
			}
			long cityId = intent.getLongExtra(CITY_ID_KEY, -1);
			String selectedLang = intent.getStringExtra(SELECTED_LANG_KEY);
			if (cityId != -1) {
				WikivoyageArticleDialogFragment.showInstance(app, getSupportFragmentManager(), cityId, selectedLang);
			}
			setIntent(null);
		}
		getMyApplication().getDownloadThread().setUiActivity(this);
	}

	@Override
	protected void onPause() {
		super.onPause();
		getMyApplication().getDownloadThread().resetUiActivity(this);
	}

	@Nullable
	private ExploreTabFragment getExploreTabFragment() {
		for (WeakReference<Fragment> ref : fragments) {
			Fragment f = ref.get();
			if (f instanceof ExploreTabFragment) {
				return (ExploreTabFragment) f;
			}
		}
		return null;
	}

	@Nullable
	private SavedArticlesTabFragment getSavedArticlesTabFragment() {
		for (WeakReference<Fragment> ref : fragments) {
			Fragment f = ref.get();
			if (f instanceof SavedArticlesTabFragment) {
				return (SavedArticlesTabFragment) f;
			}
		}
		return null;
	}

	@Override
	public void onDialogFragmentResult(@NonNull String tag, int resultCode, @Nullable Bundle data) {
		if (tag.equals(WikivoyageOptionsBottomSheetDialogFragment.TAG)) {
			switch (resultCode) {
				case WikivoyageOptionsBottomSheetDialogFragment.DOWNLOAD_IMAGES_CHANGED:
				case WikivoyageOptionsBottomSheetDialogFragment.CACHE_CLEARED:
					invalidateTabAdapters();
					break;
				case WikivoyageOptionsBottomSheetDialogFragment.TRAVEL_BOOK_CHANGED:
					populateData();
					break;
			}
		}
	}

	@Override
	public void newDownloadIndexes() {
		ExploreTabFragment exploreTabFragment = getExploreTabFragment();
		if (exploreTabFragment != null) {
			exploreTabFragment.newDownloadIndexes();
		}
	}

	@Override
	public void downloadInProgress() {
		ExploreTabFragment exploreTabFragment = getExploreTabFragment();
		if (exploreTabFragment != null) {
			exploreTabFragment.downloadInProgress();
		}
	}

	@Override
	public void downloadHasFinished() {
		ExploreTabFragment exploreTabFragment = getExploreTabFragment();
		if (exploreTabFragment != null) {
			exploreTabFragment.downloadHasFinished();
		}
	}

	private void applyIntentParameters(Intent intent, TravelArticle article) {
		intent.putExtra(TAB_SELECTED, viewPager.getCurrentItem());
		intent.putExtra(CITY_ID_KEY, article.getTripId());
		intent.putExtra(SELECTED_LANG_KEY, article.getLang());
	}

	public void setArticle(TravelArticle article) {
		Intent intent = new Intent(app, WikivoyageExploreActivity.class);
		applyIntentParameters(intent, article);
		setIntent(intent);
	}

	protected Drawable getContentIcon(int id) {
		return getIcon(id, R.color.icon_color);
	}

	protected Drawable getActiveIcon(@DrawableRes int iconId) {
		return getIcon(iconId, nightMode ? R.color.wikivoyage_active_dark : R.color.wikivoyage_active_light);
	}

	protected Drawable getIcon(@DrawableRes int id, @ColorRes int colorId) {
		return app.getIconsCache().getIcon(id, colorId);
	}

	@ColorRes
	protected int getStatusBarColor() {
		return nightMode ? R.color.status_bar_wikivoyage_dark : R.color.status_bar_wikivoyage_light;
	}

	@ColorInt
	protected int getResolvedColor(@ColorRes int colorId) {
		return ContextCompat.getColor(app, colorId);
	}

	public void populateData() {
		switchProgressBarVisibility(true);
		new LoadWikivoyageData(this).execute();
	}

	private void onDataLoaded() {
		switchProgressBarVisibility(false);
		updateSearchBarVisibility();
		updateFragments();
	}

	private void updateFragments() {
		ExploreTabFragment exploreTabFragment = getExploreTabFragment();
		SavedArticlesTabFragment savedArticlesTabFragment = getSavedArticlesTabFragment();
		if (exploreTabFragment != null && savedArticlesTabFragment != null) {
			exploreTabFragment.populateData();
			savedArticlesTabFragment.savedArticlesUpdated();
			updateNeeded = false;
		} else {
			updateNeeded = true;
		}
	}

	private void updateSearchBarVisibility() {
		boolean show = app.getTravelDbHelper().getSelectedTravelBook() != null;
		findViewById(R.id.search_box).setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private void switchProgressBarVisibility(boolean show) {
		findViewById(R.id.progress_bar).setVisibility(show ? View.VISIBLE : View.GONE);
	}

	private void invalidateTabAdapters() {
		ExploreTabFragment exploreTabFragment = getExploreTabFragment();
		if (exploreTabFragment != null) {
			exploreTabFragment.invalidateAdapter();
		}
		SavedArticlesTabFragment savedArticlesTabFragment = getSavedArticlesTabFragment();
		if (savedArticlesTabFragment != null) {
			savedArticlesTabFragment.invalidateAdapter();
		}
	}

	public void onTabFragmentResume(Fragment fragment) {
		if (updateNeeded) {
			updateFragments();
		}
	}

	private static class LoadWikivoyageData extends AsyncTask<Void, Void, Void> {

		private WeakReference<WikivoyageExploreActivity> activityRef;
		private TravelDbHelper travelDbHelper;

		LoadWikivoyageData(WikivoyageExploreActivity activity) {
			travelDbHelper = activity.getMyApplication().getTravelDbHelper();
			activityRef = new WeakReference<>(activity);
		}

		@Override
		protected Void doInBackground(Void... params) {
			travelDbHelper.loadDataForSelectedTravelBook();
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
			WikivoyageExploreActivity activity = activityRef.get();
			if (activity != null) {
				activity.onDataLoaded();
			}
		}
	}
}
