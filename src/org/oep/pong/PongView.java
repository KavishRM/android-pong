package org.oep.pong;

import java.util.Random;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Paint.Style;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;

/**
 * This class is the main viewing window for the Pong game. All the game's
 * logic takes place within this class as well.
 * @author OEP
 *
 */
public class PongView extends View implements OnTouchListener, OnKeyListener, OnCompletionListener {
	/**
	 * This is mostly deprecated but kept around if the need
	 * to add more game states comes around.
	 */
	private State mCurrentState = State.Running;
	private State mLastState = State.Stopped;
	public static enum State { Running, Stopped}

	
	private boolean initialized = false;		// Exists to initialize the View when getWidth() and getHeight() are known
	private boolean requestNewRound = true;		// Setting this to true will start a new round on the next game loop pass
	private boolean showTitle = true;			// Overlay the Pong logo over a computerized game
	private boolean mContinue = true;			// Set this to false to KILL THE THREAD.
	private boolean mMuted = false;				// Mute sounds.
	
	/**
	 * These variables concern the paddles, controlling their touch zones, lives, last
	 * position where the player touched, and whether or not the paddle is controlled by
	 * AI.
	 */
	private Rect mRedPaddleRect = new Rect();
	private Rect mBluePaddleRect = new Rect();
	private Rect mRedTouchBox, mBlueTouchBox, mPauseTouchBox;
	private float mRedLastTouch = 0;
	private float mBlueLastTouch = 0;
	private int mRedLives;
	private int mBlueLives;
	private boolean mRedIsPlayer = false;
	private boolean mBlueIsPlayer = false;
	
	/**
	 * Controls the framerate of the game. mFrameSkips is amount to increment mFramesPerSecond when
	 * the paddle hits the ball. As the game progresses, the framerate will speed up to make it more
	 * difficult for human players.
	 */
	private int mFramesPerSecond = 30;
	private int mFrameSkips = 5;
	private long mLastFrame = 0;
	
	/**
	 * The Ball variables. mDX/mDY are set after the position of the ball is manipulated to get
	 * how much dx or dy the ball has accomplished. mBallCounter when >0 means the ball is held
	 * still and will blink for a while.
	 */
	private Point mBallPosition;
	private int mBallAngle;
	private int mDX;
	private int mDY;
	private int mBallCounter = 60;
	private static int mBallSpeed = 4;
	private static int mPaddleSpeed = mBallSpeed - 2;
	
	/**
	 * Who doesn't love random numbers?
	 */
	private static final Random RNG = new Random();
	
	private final Paint mPaint = new Paint();
	
	/**
	 * These static variables control a few constants for the game.
	 */
	private static final int BALL_RADIUS = 4;
	private static final int PADDLE_THICKNESS = 10;
	private static final int PADDLE_WIDTH = 40;
	private static final int PADDING = 3;
	private static final int SCROLL_SENSITIVITY = 20 * mPaddleSpeed;
	
	/**
	 * Controls how fast we refresh
	 */
	private RefreshHandler mRedrawHandler = new RefreshHandler();
	
	private MediaPlayer mWallHit, mPaddleHit;
	private MediaPlayer mMissTone;
	private MediaPlayer mWinTone;

	/**
	 * An overloaded class that repaints this view in a separate thread.
	 * Calling PongView.update() should initiate the thread.
	 * @author OEP
	 *
	 */
	class RefreshHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			PongView.this.update();
			PongView.this.invalidate(); // Mark the view as 'dirty'
		}
		
		public void sleep(long delay) {
			this.removeMessages(0);
			this.sendMessageDelayed(obtainMessage(0), delay);
		}
	}

    /**
     * Creates a new PongView within some context
     * @param context
     * @param attrs
     */
    public PongView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPongView();
    }

    public PongView(Context context, AttributeSet attrs, int defStyle) {
    	super(context, attrs, defStyle);
    	initPongView();
    }
    
    /**
     * The main loop. Call this to update the game state.
     */
    public void update() {
    	if(getHeight() == 0 || getWidth() == 0) {
    		mRedrawHandler.sleep(1000 / mFramesPerSecond);
    		return;
    	}
    	
    	if(!initialized) {
    		nextRound();
    		newGame();
    		initialized = true;
    	}
    	
    	long now = System.currentTimeMillis();
    	if(gameRunning() && mCurrentState != State.Stopped) {
	    	if(now - mLastFrame >= 1000 / mFramesPerSecond) {
	    		if(requestNewRound) {
	    			nextRound();
	    			requestNewRound = false;
	    		}
	    		doGameLogic();
	    	}
    	}
    	
    	// We will take this much time off of the next update() call to normalize for
    	// CPU time used updating the game state.
    	
    	if(mContinue) {
    		long diff = System.currentTimeMillis() - now;
    		mRedrawHandler.sleep(Math.max(0, (1000 / mFramesPerSecond) - diff) );
    	}
    }

    /**
     * All of the game's logic (per game iteration) is in this function.
     * Given some initial game state, it computes the next game state.
     */
	private void doGameLogic() {
		int px = mBallPosition.getX();
		int py = mBallPosition.getY();
		
		// Move the ball
		if(mBallCounter == 0) {
			mBallPosition.set(
					normalizeBallX((int) (px + mBallSpeed * Math.cos(mBallAngle * Math.PI / 180.)) ), 
					py + mBallSpeed * Math.sin(mBallAngle * Math.PI / 180.)
					);
		}
		else {
			mBallCounter = Math.max(0, mBallCounter - 1);
		}
		
		mDX = mBallPosition.getX() - px;
		mDY = mBallPosition.getY() - py;
		
		// Shake it up if it appears to not be moving vertically
		if(py == mBallPosition.getY()) {
			mBallAngle = RNG.nextInt(360);
		}
		
		// Do some basic paddle AI
		if(!mRedIsPlayer)
			doAI(mRedPaddleRect);
		else 
			movePaddleTorward(mRedPaddleRect, 8 * mPaddleSpeed, mRedLastTouch);
		
		
		if(!mBlueIsPlayer)
			doAI(mBluePaddleRect);
		else
			movePaddleTorward(mBluePaddleRect, 8 * mPaddleSpeed, mBlueLastTouch);
		
		// See if all is lost
		if(mBallPosition.getY() >= getHeight()) {
			requestNewRound = true;
			mBlueLives = Math.max(0, mBlueLives - 1);
			
			if(mBlueLives != 0 || showTitle) playSound(mMissTone);
			else playSound(mWinTone);
		}
		else if (mBallPosition.getY() <= 0) {
			requestNewRound = true;
			mRedLives = Math.max(0, mRedLives - 1);
			if(mRedLives != 0 || showTitle) playSound(mMissTone);
			else playSound(mWinTone);
		}
		
		// Handle bouncing off of a wall
		if(mBallPosition.getX() == BALL_RADIUS || mBallPosition.getX() == getWidth() - BALL_RADIUS) {
			bounceBallVertical();
			if(mBallPosition.getX() == BALL_RADIUS)
				mBallPosition.translate(1, 0);
			else
				mBallPosition.translate(-1, 0);
		}
		
		// Bouncing off the paddles
		if(mBallAngle >= 180 && ballCollides(mRedPaddleRect) ) {
			bounceBallHorizontal();
			normalizeBallCollision(mRedPaddleRect);
			increaseDifficulty();
		}
		else if(mBallAngle < 180 && ballCollides(mBluePaddleRect)) {
			bounceBallHorizontal();
			normalizeBallCollision(mBluePaddleRect);
			increaseDifficulty();
		}	
	}

	/**
	 * Moves the paddle toward a specific x-coordinate without overshooting it.
	 * @param r, the Rect object to move.
	 * @param speed, the speed at which the paddle moves at maximum.
	 * @param x, the x-coordinate to move to.
	 */
	private void movePaddleTorward(Rect r, int speed, float x) {
		int dx = (int) Math.abs(r.centerX() - x);
		
		if(x < r.centerX()) {
			r.offset( (dx > speed) ? -speed : -dx, 0);
		}
		else if(x > r.centerX()) {
			r.offset( (dx > speed) ? speed : dx, 0);
		}
	}

	/**
	 * A generalized Pong AI player. Takes a Rect object and a Ball, computes where the ball will
	 * be when ball.y == rect.y, and tries to move toward that x-coordinate. If the ball is moving
	 * straight it will try to clip the ball with the edge of the paddle.
	 * @param cpu
	 */
	private void doAI(Rect cpu) {
		// vy = speed * sin(angle)
		int vy = mDY;
		
		// Special case: move torward the center if the ball is blinking
		if(mBallCounter > 0)
			movePaddleTorward(cpu, mPaddleSpeed, getWidth() / 2);
		
		// Something is wrong if vy = 0.. let's wait until things fix themselves
		if(vy == 0) return;
		
		// vx = speed * cos(angle)
		int vx = mDX;
		
		// time of arrival = (ball.y - paddle.y) / vy;
		int eta = (mBallPosition.getY() - cpu.centerY()) / -vy;
		
		int x = mBallPosition.getX();
		int y = mBallPosition.getY();
		
		// Move toward the ball's current x position if it isn't coming right to us.
		while(eta <= 0) {
			y += vy;
			x = normalizeBallX(x + vx);
			
			if(y <= mRedPaddleRect.centerY() && vy < 0 || y >= mBluePaddleRect.centerY() && vy > 0) {
				vy = -vy;
			}
			
			if(x == BALL_RADIUS || x == getWidth() - BALL_RADIUS) {
				vx = -vx;
			}
			
			eta = (y - cpu.centerY()) / -vy;
		}
		
		// Calculate its trajectory
		while(y != cpu.centerY()) {
			y = (Math.abs(cpu.centerY() - y) > Math.abs(vy)) ? y + vy : cpu.centerY();
			x = normalizeBallX(x + vx);
			
			if(x == BALL_RADIUS || x == getWidth() - BALL_RADIUS) {
				vx = -vx;
			}
		}
		
		/*
		 * SPECIAL ATTACKS!!!
		 */
		
		// If the ball is blinking, move back to the center
		if(mBallCounter > 0) {
			x = getWidth() / 2;
		}
		
		// Try to give it a little kick if vx = 0
		int salt = (int) (System.currentTimeMillis() / 10000);
		Random r = new Random(cpu.centerY() + mBallAngle + salt);
		x += r.nextInt(2 * PADDLE_WIDTH - (PADDLE_WIDTH / 5)) - PADDLE_WIDTH + (PADDLE_WIDTH / 10);
		movePaddleTorward(cpu, mPaddleSpeed, x);
	}
	
	/**
	 * Knocks up the framerate a bit to keep it difficult.
	 */
	private void increaseDifficulty() {
		if(mFramesPerSecond < 250) {
			mFramesPerSecond += mFrameSkips;
			mFrameSkips++;
		}
	}

	/**
	 * Provides such faculties as normalizing where the ball
	 * will be painted as well as varying the angle at which the ball will fly
	 * when it bounces off the paddle.
	 * @param r
	 */
	private void normalizeBallCollision(Rect r) {
		int x = mBallPosition.getX();
		int y = mBallPosition.getY();
		
		// Quit if the ball is outside the width of the paddle
		if(x < r.left || x > r.right) {
			return;
		}
		
		// Case if ball is above the paddle
		if(y < r.top) {
			mBallPosition.set(x, Math.min(y, r.top - BALL_RADIUS));
		}
		else if(y > r.bottom) {
			mBallPosition.set(x, Math.max(y, r.bottom + BALL_RADIUS));
		}
		
		
		
		int dA = 40 * Math.abs(x - r.centerX()) / Math.abs(r.left - r.centerX());
		if(mBallAngle > 180 && x < r.centerX() || mBallAngle < 180 && x > r.centerX()) {
			mBallAngle = safeRotate(mBallAngle, -dA);
		}
		else if(mBallAngle > 180 && x > r.centerX() || mBallAngle < 180 && x < r.centerX()) {
			mBallAngle = safeRotate(mBallAngle, dA);
		}
	}
	
	/**
	 * Rotate the ball without extending beyond bounds which would create a case
	 * where VY = 0.
	 * @param ballAngle
	 * @param da
	 * @return
	 */
	private int safeRotate(int angle, int da) {
		int dy, add;
		while(da != 0) {
			add = (da > 0) ? 1 : -1;
			angle += add;
			da -= add;
			
			dy = (int) (mBallSpeed * Math.sin(angle * Math.PI / 180));
			if(dy == 0) {
				return angle - add;
			}
		}
		return angle;
	}

	/**
	 * Given it a coordinate, it transforms it into a proper x-coordinate for the ball.
	 * @param x, the x-coord to transform
	 * @return
	 */
	private int normalizeBallX(int x) {
		return Math.max(BALL_RADIUS, Math.min(x, getWidth() - BALL_RADIUS));
	}

	/**
	 * Tells us if the ball collides with a rectangle.
	 * @param r, the rectangle
	 * @return true if the ball is colliding, false if not
	 */
	private boolean ballCollides(Rect r) {
		int x = mBallPosition.getX(); int y = mBallPosition.getY();
		return y >= mRedPaddleRect.bottom && y <= mBluePaddleRect.bottom && 
			x >= r.left && x <= r.right && 
			y >= r.top - BALL_RADIUS && y <= r.bottom + BALL_RADIUS; 
	}

	/**
	 * Method bounces the ball across a vertical axis. Seriously it's that easy.
	 * Math failed me when figuring this out so I guessed instead.
	 */
	private void bounceBallVertical() {
		mBallAngle = (540 - mBallAngle) % 360;
		playSound(mWallHit);
	}

	/**
	 * Bounce the ball off a horizontal axis.
	 */
	private void bounceBallHorizontal() {
		// Amazingly enough...
		mBallAngle = (360 - mBallAngle) % 360;
		playSound(mPaddleHit);
	}

	/**
	 * Set the state, start a new round, start the loop if needed.
	 * @param next, the next state
	 */
	public void setMode(State next) {
    	mCurrentState = next;
    	nextRound();
    	update();
    }
    
    /**
     * Set the paddles to their initial states and as well the ball.
     */
    private void initPongView() {
    	setOnTouchListener(this);
    	setOnKeyListener(this);
    	setFocusable(true);
    	resetPaddles();
    	mBallPosition = new Point(getWidth() / 2, getHeight() / 2);
    	
    	mWallHit = loadSound(R.raw.wall);
    	mPaddleHit = loadSound(R.raw.paddle);
    	mMissTone = loadSound(R.raw.ballmiss);
    	mWinTone = loadSound(R.raw.wintone);
    	
    	// Grab the muted preference
    	Context ctx = this.getContext();
    	SharedPreferences settings = ctx.getSharedPreferences(Pong.DB_PREFS, 0);
    	mMuted = settings.getBoolean(Pong.PREF_MUTED, mMuted);
    }
    
    /**
     * Reset the paddles/touchboxes/framespersecond/ballcounter for the next round.
     */
    private void nextRound() {
    	mRedTouchBox = new Rect(0,0,getWidth(),getHeight() / 8);
    	mBlueTouchBox = new Rect(0, 7 * getHeight() / 8, getWidth(), getHeight());
    	
    	int min = Math.min(getWidth() / 4, getHeight() / 4);
    	int xmid = getWidth() / 2;
    	int ymid = getHeight() / 2;
    	mPauseTouchBox = new Rect(xmid - min, ymid - min, xmid + min, ymid + min);
    	
    	realignPaddles();
    	resetBall();
    	mFramesPerSecond = 30;
    	mBallCounter = 60;
    }
    
    private void realignPaddles() {
		mRedPaddleRect.top = mRedTouchBox.bottom + PADDING;
		mRedPaddleRect.bottom = mRedPaddleRect.top + PADDLE_THICKNESS;
		
		mBluePaddleRect.bottom = mBlueTouchBox.top - PADDING;
		mBluePaddleRect.top = mBluePaddleRect.bottom - PADDLE_THICKNESS;
	}

	/**
     * Reset paddles to an initial state.
     */
    private void resetPaddles() {
    	mRedPaddleRect.top = PADDING;
    	mRedPaddleRect.bottom = PADDING + PADDLE_THICKNESS;
    	
    	mBluePaddleRect.top = getHeight() - PADDING - PADDLE_THICKNESS;
    	mBluePaddleRect.bottom = getHeight() - PADDING;
    	
    	mBluePaddleRect.left = mRedPaddleRect.left = getWidth() / 2 - PADDLE_WIDTH;
    	mBluePaddleRect.right = mRedPaddleRect.right = getWidth() / 2 + PADDLE_WIDTH;
    	
    	mRedLastTouch = getWidth() / 2;
    	mBlueLastTouch = getWidth() / 2;
    }
    
    /**
     * Reset ball to an initial state
     */
    private void resetBall() {
    	mBallPosition.set(getWidth() / 2, getHeight() / 2);
    	mBallAngle = RNG.nextInt(360);
    	mBallCounter = 60;
    }
    
    /**
     * Use for keeping track of a position.
     * @author pkilgo
     *
     */
    class Point {
    	private int x, y;
    	Point() {
    		x = 0; y = 0;
    	}
    	
    	Point(int x, int y) {
    		this.x = x; this.y = y;
    	}
    	
    	public int getX() { return x; }
    	public int getY() { return y ; }
    	public void set(double d, double e) { this.x = (int) d; this.y = (int) e; }
    	
    	public void translate(int i, int j) { this.x += i; this.y += j; }
    	
    	@Override
    	public String toString() {
    		return "Point: (" + x + ", " + y + ")";
    	}
    }
    
    public void onSizeChanged(int w, int h, int ow, int oh) {
    	mPaddleSpeed = Math.max(1, w / 160);
    }
    
    /**
     * Paints the game!
     */
    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    	Context context = getContext();
        
        // Draw the paddles / touch boundaries
        mPaint.setStyle(Style.FILL);
        mPaint.setColor(Color.RED);
        canvas.drawRect(mRedPaddleRect, mPaint);
        
        
        
        if(gameRunning() && mRedIsPlayer && mCurrentState == State.Running)
        	canvas.drawLine(mRedTouchBox.left, mRedTouchBox.bottom, mRedTouchBox.right, mRedTouchBox.bottom, mPaint);
    
        // Draw Blue's stuff
        mPaint.setColor(Color.BLUE);
        canvas.drawRect(mBluePaddleRect, mPaint);
        
        if(gameRunning() && mBlueIsPlayer && mCurrentState == State.Running)
        	canvas.drawLine(mBlueTouchBox.left, mBlueTouchBox.top, mBlueTouchBox.right, mBlueTouchBox.top, mPaint);
        
        // Draw ball stuff
        mPaint.setStyle(Style.FILL);
        mPaint.setColor(Color.WHITE);
        
        if((mBallCounter / 10) % 2 == 1 || mBallCounter == 0)
        	canvas.drawCircle(mBallPosition.getX(), mBallPosition.getY(), BALL_RADIUS, mPaint);
        
        // If either is a not a player, blink and let them know they can join in!
        // This blinks with the ball.
        if(!showTitle && (mBallCounter / 10) % 2 == 1 && mBallCounter > 0) {
        	String join = context.getString(R.string.join_in);
        	int joinw = (int) mPaint.measureText(join);
        	
        	if(!mRedIsPlayer) {
        		mPaint.setColor(Color.RED);
        		canvas.drawText(join, getWidth() / 2 - joinw / 2, mRedTouchBox.centerY(), mPaint);
        	}
        	
        	if(!mBlueIsPlayer) {
        		mPaint.setColor(Color.BLUE);
        		canvas.drawText(join, getWidth() / 2 - joinw / 2, mBlueTouchBox.centerY(), mPaint);
        	}
        }
        
        // Show where the player can touch to pause the game
        if(!showTitle && (mBallCounter / 10) % 2 == 0 && mBallCounter > 0) {
        	String pause = context.getString(R.string.pause);
        	int pausew = (int) mPaint.measureText(pause);
        
        	mPaint.setColor(Color.GREEN);
        	mPaint.setStyle(Style.STROKE);
        	canvas.drawRect(mPauseTouchBox, mPaint);
        	canvas.drawText(pause, getWidth() / 2 - pausew / 2, getHeight() / 2, mPaint);
        }

    	// Paint a PAUSED message
        if(gameRunning() && mCurrentState == State.Stopped) {
        	String s = context.getString(R.string.paused);
        	int width = (int) mPaint.measureText(s);
        	int height = (int) (mPaint.ascent() + mPaint.descent()); 
        	mPaint.setColor(Color.WHITE);
        	canvas.drawText(s, getWidth() / 2 - width / 2, getHeight() / 2 - height / 2, mPaint);
        }
        
        // Draw a 'lives' counter
        if(!showTitle) {
        	mPaint.setColor(Color.WHITE);
        	for(int i = 0; i < mRedLives; i++) {
        		canvas.drawCircle(BALL_RADIUS + PADDING + i * (2 * BALL_RADIUS + PADDING),
        				PADDING + BALL_RADIUS,
        				BALL_RADIUS,
        				mPaint);
        	}
        	
        	for(int i = 0; i < mBlueLives; i++) {
        		canvas.drawCircle(BALL_RADIUS + PADDING + i * (2 * BALL_RADIUS + PADDING),
        				getHeight() - PADDING - BALL_RADIUS,
        				BALL_RADIUS,
        				mPaint);
        	}
        }
        
        // Announce the winner!
        if(!gameRunning()) {
        	mPaint.setColor(Color.GREEN);
        	String s = "You both lose";
        	
        	if(mBlueLives == 0) {
        		s = context.getString(R.string.red_wins);
        		mPaint.setColor(Color.RED);
        	}
        	else if(mRedLives == 0) {
        		s = context.getString(R.string.blue_wins);
        		mPaint.setColor(Color.BLUE);
        	}
        	
        	int width = (int) mPaint.measureText(s);
        	int height = (int) (mPaint.ascent() + mPaint.descent()); 
        	canvas.drawText(s, getWidth() / 2 - width / 2, getHeight() / 2 - height / 2, mPaint);
        }
        
        // Draw the Title text
        if(showTitle) {
        	Bitmap image = BitmapFactory.decodeResource(context.getResources(), R.drawable.pong);
        	
        	canvas.drawBitmap(image, getWidth() / 2 - image.getWidth() / 2, 
        			getHeight() / 2 - image.getHeight() / 2, mPaint);
        	
        	String prompt = context.getString(R.string.menu_prompt);
       	
        	mPaint.setColor(Color.WHITE);
        	
        	int nextLine = 3 * getHeight() / 4;
        	int w = (int) mPaint.measureText(prompt);
        	canvas.drawText(prompt, getWidth() / 2 - w / 2, nextLine, mPaint);
        }
        
        // canvas.drawText("Angle: " + mBallAngle, 0, getHeight() / 2, mPaint);
    }

    /**
     * Touching is the method of movement. Touching the touchscreen, that is.
     * A player can join in simply by touching where they would in a normal
     * game.
     */
	public boolean onTouch(View v, MotionEvent mo) {
		if(v != this || !gameRunning() || showTitle) return false;

		int tx = (int) mo.getX();
		int ty = (int) mo.getY();
		
		// Bottom paddle moves when we are playing in one or two player mode and the touch
		// was in the lower quartile of the screen.
		if(mBlueIsPlayer && mBlueTouchBox.contains(tx,ty)) {
			mBlueLastTouch = tx;
		}
		else if(mRedIsPlayer && mRedTouchBox.contains(tx,ty)) {
			mRedLastTouch = tx;
		}
		else if(mo.getAction() == MotionEvent.ACTION_DOWN && mPauseTouchBox.contains(tx, ty)) {
			if(mCurrentState != State.Stopped) {
				mLastState = mCurrentState;
				mCurrentState = State.Stopped;
			}
			else {
				mCurrentState = mLastState;
				mLastState = State.Stopped;
			}
		}
				
		// In case a player wants to join in...
		if(mo.getAction() == MotionEvent.ACTION_DOWN) {
			if(!mBlueIsPlayer && mBlueTouchBox.contains(tx,ty)) {
				mBlueIsPlayer = true;
			}
			else if(!mRedIsPlayer && mRedTouchBox.contains(tx,ty)) {
				mRedIsPlayer = true;
			}
		}
		
		
		return true;
	}
	
	@Override
	public boolean onTrackballEvent(MotionEvent event) {
		if(!gameRunning() || showTitle) return false;
		
		if(mBlueIsPlayer == false) {
			mBlueIsPlayer = true;
			mBlueLastTouch = mBluePaddleRect.centerX();
		}
		
		switch(event.getAction()) {
		case MotionEvent.ACTION_MOVE:
			mBlueLastTouch = Math.max(0, Math.min(getWidth(), mBlueLastTouch + SCROLL_SENSITIVITY * event.getX()));
			break;
		}
		
		return true;
	}
    
	/**
	 * Reset the lives, paddles and the like for a new game.
	 */
	public void newGame() {
		mRedLives = 3;
		mBlueLives = 3;
		mFrameSkips = 5;
		
		resetPaddles();
		nextRound();
		
		resumeLastState();
	}
	
	/**
	 * This is kind of useless as well.
	 */
	private void resumeLastState() {
		if(mLastState == State.Stopped && mCurrentState == State.Stopped) {
			mCurrentState = State.Running;
		}
		else if(mCurrentState != State.Stopped) {
			// Do nothing
		}
		else if(mLastState != State.Stopped) {
			mCurrentState = mLastState;
			mLastState = State.Stopped;
		}
	}
	
	public boolean gameRunning() {
		return showTitle || (mRedLives > 0 && mBlueLives > 0);
	}
	
	public void setShowTitle(boolean b) {
		showTitle = b;
	}
	
	public void pause() {
		if(!showTitle) {
			mLastState = mCurrentState;
			mCurrentState = State.Stopped;
		}
	}
	
	public boolean titleShowing() {
		return showTitle;
	}


	public boolean onKey(View v, int keyCode, KeyEvent event) {
		return false;
	}

	public void setPlayerControl(boolean red, boolean blue) {
		mRedIsPlayer = red;
		mBlueIsPlayer = blue;
	}

	public void onCompletion(MediaPlayer mp) {
		mp.seekTo(0);
	}
	
	public void resume() {
		mContinue = true;
		update();
	}
	
	public void stop() {
		mContinue = false;
	}
	
	public void toggleMuted() {
		this.setMuted(!mMuted);
	}
	
	public void setMuted(boolean b) {
		// Set the in-memory flag
		mMuted = b;
		
		// Grab a preference editor
		Context ctx = this.getContext();
		SharedPreferences settings = ctx.getSharedPreferences(Pong.DB_PREFS, 0);
		SharedPreferences.Editor editor = settings.edit();
		
		// Save the value
		editor.putBoolean(Pong.PREF_MUTED, b);
		editor.commit();
	}
	
	/**
	 * Put yer resources in year and we'll release em!
	 */
	public void releaseResources() {
		mWallHit.release();
		mPaddleHit.release();
		mWinTone.release();
		mMissTone.release();
	}
	
	private MediaPlayer loadSound(int rid) {
		MediaPlayer mp = MediaPlayer.create(getContext(), rid);
		mp.setOnCompletionListener(this);
		return mp;
	}
	
	private void playSound(MediaPlayer mp) {
		if(mMuted == true) return;
		
		if(!mp.isPlaying()) {
			mp.setVolume(0.5f, 0.5f);
			mp.start();
		}
	}
}
