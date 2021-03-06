/*
This file is part of Free Analysis and Interactive Reconstruction
for Structured Illumination Microscopy (fairSIM).

fairSIM is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

fairSIM is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with fairSIM.  If not, see <http://www.gnu.org/licenses/>
*/

package org.fairsim.sim_algorithm;

import org.fairsim.linalg.*;
import org.fairsim.utils.Tool;
import org.fairsim.utils.Conf;
import org.fairsim.utils.ImageDisplay;

/** High-level parts of the SIM algorithm */
public class SimAlgorithm {

    /** Run the SIM parameter estimation 
     * @param param  The SIM parameter instance to work on
     * @param inFFT  The input images (in Fourier space)
     * @param fitBand On which band to perform the kx,ky fit
     * @param fitExclude How much (in fraction of OTF support) to exclude from fit
     * @param idf    ImageDisplayFactory for intermediate output (may be null)
     * @param visualFeedback Feedback Amount of visual feedback, 0..4
     * @param tEst   Runtime measurement (may be null) */
    public static void estimateParameters( final SimParam param, 
	Vec2d.Cplx [][] inFFT, final int fitBand, final double fitExclude,
	ImageDisplay.Factory idf, 
	int visualFeedback, Tool.Timer tEst ) {

	final int w = inFFT[0][0].vectorWidth(), h = inFFT[0][0].vectorHeight();
	final OtfProvider otfPr = param.otf();

	ImageDisplay pwSt=null,pwSt2=null, spSt=null, spSt2=null;
	if ((visualFeedback>0)&&(idf!=null)) {
	    pwSt  = idf.create(w,h, "Power Spectra" );
	    spSt  = idf.create(w,h, "Spatial images");
	    pwSt2 = idf.create(2*w,2*h, "Power Spectra" );
	    spSt2 = idf.create(2*w,2*h, "Spatial images");
	}

	if (fitBand!=1 && fitBand!=2 ) throw new RuntimeException(
	    "Fitband neither 1 nor 2");

	if (tEst!=null) tEst.start();
    
	// The attenuation vector helps well to fade out the DC component,
	// which is uninteresting for the correlation anyway
	Vec2d.Real otfAtt = Vec2d.createReal( param );
	otfPr.writeAttenuationVector( otfAtt, .99, 0.15*otfPr.getCutoff(), 0, 0  ); 
	
	// loop through pattern directions
	for (int angIdx=0; angIdx<param.nrDir(); angIdx++) {
	
	    final SimParam.Dir dir = param.dir(angIdx);

	    // idx of low band (phase detection) and high band (shift vector detection)
	    // will be the same for two-beam
	    final int lb = 1;
	    final int hb = (param.dir(angIdx).nrBand()==3)?(3):(1);
	    final int fb = (fitBand==1)?(lb):(hb);

	    // compute band separation
	    Vec2d.Cplx [] separate = Vec2d.createArrayCplx( dir.nrComp(), w, h);
	    BandSeparation.separateBands( inFFT[angIdx] , separate , 
		0, dir.nrBand(), null);

	    // duplicate vectors, as they are modified for coarse correlation
	    Vec2d.Cplx c0 = separate[0].duplicate();
	    Vec2d.Cplx c1 = separate[lb].duplicate();
	    Vec2d.Cplx c2 = separate[hb].duplicate();

	    // dampen region around DC 
	    c0.times( otfAtt );
	    c1.times( otfAtt );
	    c2.times( otfAtt ); 
	    
	    // compute correlation: ifft, mult. in spatial, fft back
	    Transforms.fft2d( c0, true);
	    Transforms.fft2d( c1, true);
	    Transforms.fft2d( c2, true);
	    c1.timesConj( c0 );
	    c2.timesConj( c0 );
	    Transforms.fft2d( c1, false);
	    Transforms.fft2d( c2, false);
       
	    // find the highest peak in corr of band0 to highest band 
	    // with min dist 0.5*otfCutoff from origin, store in 'param'
	    double minDist = fitExclude * otfPr.getCutoff() / param.pxlSizeCyclesMicron();
	    double [] peak = Correlation.locatePeak(  (fitBand==1)?(c1):(c2) , minDist );
	    
	    Tool.trace(String.format("Peak: (dir %1d) located (min %4.0f) at x %5.0f y %5.0f",
		angIdx, minDist, peak[0], peak[1]));
	    
	    // fit the peak to sub-pixel precision by cross-correlation of
	    // Fourier-shifted components
	    Vec2d.Real cntrl    = Vec2d.createReal(30,10);
	    peak = Correlation.fitPeak( separate[0], separate[fb], 0, fitBand, otfPr,
		-peak[0], -peak[1], 0.05, 2.5, cntrl );

	    // Now, either three beam / 3 bands ...
	    if (lb!=hb) {
		
		// peak should contain the shift band0<->band2, so if band0<->band1
		// was fitted, multiply by 2
		if (fitBand==1) {
		    peak[0]*=2; peak[1]*=2;
		}

		// At the peak position found, extract phase and modulation from band0 <-> band 1
		Cplx.Double p1 = Correlation.getPeak( separate[0], separate[lb], 
		    0, 1, otfPr, peak[0]/2, peak[1]/2, 0.05 );

		// Extract modulation from band0 <-> band 2
		Cplx.Double p2 = Correlation.getPeak( separate[0], separate[hb], 
		    0, 2, otfPr, peak[0], peak[1], 0.05 );

		Tool.trace(
		    String.format("Peak: (dir %1d): fitted --> x %7.3f y %7.3f p %7.3f (m %7.3f, %7.3f)", 
		    angIdx, peak[0], peak[1], p1.phase(), p1.hypot(), p2.hypot() ));
	
		// store the result
		param.dir(angIdx).setPxPy(   -peak[0], -peak[1] );
		param.dir(angIdx).setPhaOff( p1.phase() );
		param.dir(angIdx).setModulation( 1, p1.hypot() );
		param.dir(angIdx).setModulation( 2, p2.hypot() );
	    }
	    
	    // ... or two-beam / 2 bands
	    if (lb==hb) {
		// get everything from one correlation band0 to band1
		Cplx.Double p1 = Correlation.getPeak( separate[0], separate[1], 
		    0, 1, otfPr, peak[0], peak[1], 0.05 );

		Tool.trace(
		    String.format("Peak: (dir %1d): fitted --> x %7.3f y %7.3f p %7.3f (m %7.3f)", 
		    angIdx, peak[0], peak[1], p1.phase(), p1.hypot() ));
	
		// store the result
		param.dir(angIdx).setPxPy(   -peak[0], -peak[1] );
		param.dir(angIdx).setPhaOff( p1.phase() );
		param.dir(angIdx).setModulation( 1, p1.hypot() );
	    }



	    // --- output visual feedback of peak fit ---
	    if (visualFeedback>0) {
		
		// mark region excluded from peak finder
		// output the peaks found, with circles marking them, and the fit result in
		// the top corner for the correlation band0<->band2
		ImageDisplay.Marker excludedDC = 
		    new ImageDisplay.Marker(w/2,h/2,minDist*2,minDist*2,true);
		
		Vec2d.Real fittedPeak = SimUtils.pwSpec( (fitBand==1)?(c1):(c2) );
		fittedPeak.paste( cntrl, 0, 0, false );
		
		double f=(lb!=hb)?(fitBand/2.):(1);

		pwSt.addImage( fittedPeak, "dir "+angIdx+" c-corr band 0<>band "+fitBand,
		    new ImageDisplay.Marker( w/2-peak[0]*f, h/2+peak[1]*f, 10, 10, true),
		    excludedDC);
		
		// if there is a low band, also add it
		if ((visualFeedback>1)&&(lb!=hb))
		    pwSt.addImage( SimUtils.pwSpec( c1 ), "dir "+angIdx+" c-corr band 0<>low",
			new ImageDisplay.Marker( w/2-peak[0]/2, h/2+peak[1]/2, 10, 10, true));
	    }
		

	    // --- output visual feedback of overlapping regions (for all bands) ---
	    if (visualFeedback>1)  
	    for (int b=1; b<param.nrBand(); b++) {	
	    
		SimParam.Dir par = param.dir(angIdx);

		// find common regions in low and high band
		Vec2d.Cplx b0 = separate[0  ].duplicate();
		Vec2d.Cplx b1 = separate[2*b].duplicate();
	    
		Correlation.commonRegion( b0, b1, 0, b, otfPr,  
		    par.px(b), par.py(b), 0.15, (b==1)?(.2):(.05), true);

		// move the high band to its correct position
		Transforms.fft2d( b1, true );
		Transforms.timesShiftVector( b1, par.px(b), -par.py(b), true );
		Transforms.fft2d( b1, false );
	
		// apply phase correction
		b1.scal( Cplx.Float.fromPhase( param.dir(angIdx).getPhaOff()*b ));
       
		// output the full shifted bands
		if ( visualFeedback>2 )  {
		    // only add band0 once	
		    if ( b==1 ) {
			Vec2d.Cplx btmp = separate[0].duplicate();
			otfPr.maskOtf( btmp, 0, 0);
			pwSt.addImage(SimUtils.pwSpec( btmp ), String.format(
			    "a%1d: full band0", angIdx, b ));
		    }

		    // add band1, band2, ...
		    Vec2d.Cplx btmp = separate[2*b].duplicate();
		    Transforms.fft2d( btmp, true );
		    Transforms.timesShiftVector( btmp, par.px(b), -par.py(b), true );
		    Transforms.fft2d( btmp, false );
		    otfPr.maskOtf( btmp, par.px(b), par.py(b));

		    pwSt.addImage(SimUtils.pwSpec( btmp ), String.format( 
			"a%1d: full band%1d (shifted %7.3f %7.3f)",
			angIdx,  b, par.px(b), par.py(b))); 
		}

		// output power spectra of common region
		pwSt.addImage(SimUtils.pwSpec( b0 ), String.format(
		    "a%1d: common region b0<>b%1d, band0", angIdx, b )); 
		pwSt.addImage(SimUtils.pwSpec( b1 ), String.format( 
		    "a%1d: common region b0<>b%1d, band%1d",angIdx, b, b)); 

		// output spatial representation of common region
		spSt.addImage(SimUtils.spatial( b0 ), String.format(
		    "a%1d: common region b0<>b%1d, band0", angIdx, b )); 
		spSt.addImage(SimUtils.spatial( b1 ), String.format( 
		    "a%1d: common region b0<>b%1d, band%1d",angIdx, b, b)); 
	    }
	
	}
    
	if (tEst!=null) tEst.stop();
	pwSt.display();
	spSt.display();
	pwSt2.display();
	spSt2.display();


    }


    /** Run the SIM reconstruction 
     * @param param  The SIM parameter instance to work on
     * @param inFFT  The input images (in Fourier space)
     * @param idf    ImageDisplayFactory for intermediate output (may be null)
     * @param visualFeedback Feedback Amount of visual feedback, 0..4
     * @param otfBeforeShift Apply the OTF before shifting bands
     * @param imgClipScale Clip zero values and scale (0..255) output images?
     * @param tRec   Runtime measurement (may be null) */
    public static void runReconstruction( final SimParam param, 
	Vec2d.Cplx [][] inFFT, ImageDisplay.Factory idf, int visualFeedback, 
	final boolean otfBeforeShift, final int imgClipScale, Tool.Timer tRec ) {

	if (tRec != null) tRec.start();	

	final int w = inFFT[0][0].vectorWidth(), h = inFFT[0][0].vectorHeight();
    
	final double apoB=.9, apoF=param.getApoCutoff(); // Bend and mag. factor of APO
	
	final OtfProvider otfPr = param.otf();

	ImageDisplay pwSt=null,pwSt2=null, spSt=null, spSt2=null;
	
	if (idf!=null) {
	    pwSt  = idf.create(w,h, "Power Spectra" );
	    spSt  = idf.create(w,h, "Spatial images");
	    pwSt2 = idf.create(2*w,2*h, "Power Spectra" );
	    spSt2 = idf.create(2*w,2*h, "Spatial images");
	}
	
	// setup WienerFilter
	Tool.tell("Setting up Wiener filter");
	WienerFilter wFilter = new WienerFilter( param );
	double wienParam     = param.getWienerFilter();

	if (visualFeedback>0) {
	    Vec2d.Real wd = wFilter.getDenominator(wienParam);
	    wd.reciproc();
	    wd.normalize();
	    Transforms.swapQuadrant( wd );
	    pwSt2.addImage(wd, "Wiener denominator");
	}
	
	// vectors to store the result
	Vec2d.Cplx fullResult    = Vec2d.createCplx( param, 2);
	
	// loop all pattern directions
	for (int angIdx = 0; angIdx < param.nrDir(); angIdx ++ ) 
	{
	    final SimParam.Dir par = param.dir(angIdx);
	    Tool.tell("Reconstr. for angle "+(angIdx+1)+"/"+param.nrDir());

	    // ----- Band separation & OTF multiplication (if before shift) -------

	    Vec2d.Cplx [] separate  = Vec2d.createArrayCplx( par.nrComp(), w, h);
	    
	    BandSeparation.separateBands( inFFT[angIdx] , separate , 
		    par.getPhases(), par.nrBand(), par.getModulations());

	    if (otfBeforeShift)
		for (int i=0; i<(par.nrBand()*2-1) ;i++)  
		    otfPr.applyOtf( separate[i], (i+1)/2);

	    // ------- Shifts to correct position ----------

	    Vec2d.Cplx [] shifted		= Vec2d.createArrayCplx(5, 2*w, 2*h);

	    // band 0 is DC, so does not need shifting, only a bigger vector
	    SimUtils.placeFreq( separate[0],  shifted[0]);
	    
	    // higher bands need shifting
	    for ( int b=1; b<par.nrBand(); b++) {
		
		Tool.trace("reconstr.: dir "+angIdx+": shift band: "+b+" to: "+par.px(b)+" "+par.py(b));
		
		// first, copy to larger vectors
		int pos = b*2, neg = (b*2)-1;	// pos/neg contr. to band
		SimUtils.placeFreq( separate[pos] , shifted[pos]);
		SimUtils.placeFreq( separate[neg] , shifted[neg]);

		// then, fourier shift
		SimUtils.fourierShift( shifted[pos] ,  par.px(b),  par.py(b) );
		SimUtils.fourierShift( shifted[neg] , -par.px(b), -par.py(b) );
	    }
	   
	    // ------ OTF multiplication or masking ------
	    
	    if (!otfBeforeShift) {
		// multiply with shifted OTF
		otfPr.applyOtf( shifted[0], 0 );
		for (int b=1; b<par.nrBand(); b++) {
		    int pos = b*2, neg = (b*2)-1;	// pos/neg contr. to band
		    otfPr.applyOtf( shifted[pos], b,  par.px(b),  par.py(b) );
		    otfPr.applyOtf( shifted[neg], b, -par.px(b), -par.py(b) );
		}
	    } else {
		// or mask for OTF support
		for (int i=0; i<(par.nrBand()*2-1) ;i++)  
		    //wFilter.maskOtf( shifted[i], angIdx, i);
		    otfPr.maskOtf( shifted[i], angIdx, i);
	    }
	    
	    // ------ Sum up result ------
	    
	    for (int i=0;i<par.nrBand()*2-1;i++)  
		fullResult.add( shifted[i] ); 
	
	    
	    // ------ Output intermediate results ------
	    
	    if (visualFeedback>0) {
	
		Tool.tell("Computing interm. results");

		// per-direction results
		Vec2d.Cplx result = Vec2d.createCplx(2*w,2*h);
		for (int i=0;i<par.nrBand()*2-1;i++)  
		    result.add( shifted[i] ); 

		// loop bands in this direction
		for (int i=0;i<par.nrBand();i++) {     

		    // get wiener denominator for (direction, band), add to full denom for this band
		    Vec2d.Real denom = wFilter.getIntermediateDenominator( angIdx, i, wienParam);
		
		    // add up +- shift for this band
		    Vec2d.Cplx thisband   = shifted[i*2];
		    if (i!=0)
			thisband.add( shifted[i*2-1] );
	
		    // output the wiener denominator
		    if (visualFeedback>1) {
			Vec2d.Real wd = denom.duplicate();
			wd.reciproc();
			wd.normalize();
			Transforms.swapQuadrant( wd );
			pwSt2.addImage( wd, String.format(
			    "a%1d: OTF/Wiener band %1d",angIdx,(i/2) ));
		    }
		    
		    // apply filter and output result
		    thisband.times( denom );
		    
		    pwSt2.addImage( SimUtils.pwSpec( thisband ) ,String.format(
			"a%1d: band %1d",angIdx,i));
		    spSt2.addImage( SimUtils.spatial( thisband, imgClipScale ) ,String.format(
			"a%1d: band %1d",angIdx,i));
		}

		// per direction wiener denominator	
		Vec2d.Real fDenom =  wFilter.getIntermediateDenominator( angIdx, wienParam);	
		result.times( fDenom );
		    
		// output the wiener denominator
		if (visualFeedback>1) {
		    Vec2d.Real wd = fDenom.duplicate();
		    wd.reciproc();
		    wd.normalize();
		    Transforms.swapQuadrant( wd );
		    pwSt2.addImage( wd, String.format(
			"a%1d: OTF/Wiener all bands",angIdx ));
		}
		
		pwSt2.addImage( SimUtils.pwSpec( result ) ,String.format(
		    "a%1d: all bands",angIdx));
		spSt2.addImage( SimUtils.spatial( result, imgClipScale ) ,String.format(
		    "a%1d: all bands",angIdx));
	    
		// power spectra before shift
		if (visualFeedback>2) { 
		    for (int i=0; i<(par.nrBand()*2-1) ;i++)  
		    pwSt.addImage( SimUtils.pwSpec( separate[i] ), String.format(
			"a%1d, sep%1d, seperated band", angIdx, i));
		}
	   
	    }


	}   
	
	// -- done loop all pattern directions, 'fullResult' now holds the image --
	
	Tool.tell("Applying filters");
	
	// multiply by wiener denominator
	Vec2d.Real denom = wFilter.getDenominator( wienParam );
	fullResult.times(denom);
	
	if (visualFeedback>0) {
	    pwSt2.addImage(  SimUtils.pwSpec( fullResult), "full (w/o APO)");
	    spSt2.addImage(  SimUtils.spatial(fullResult, imgClipScale), "full (w/o APO)");
	}

	// apply apotization filter
	Vec2d.Cplx apo = Vec2d.createCplx(2*w,2*h);
	otfPr.writeApoVector( apo, apoB, apoF);
	fullResult.times(apo);
	spSt2.addImage( SimUtils.spatial( fullResult, imgClipScale), "full result");


	if (visualFeedback>0) {
	    pwSt2.addImage( SimUtils.pwSpec( fullResult), "full result");
	}

	// Add wide-field for comparison
	if (visualFeedback>=0) {
	
	    Tool.tell("Computing wide-field");
	    
	    // obtain the low freq result
	    Vec2d.Cplx lowFreqResult = Vec2d.createCplx( param, 2);
	    
	    // have to do the separation again, result before had the OTF multiplied
	    for (int angIdx = 0; angIdx < param.nrDir(); angIdx ++ ) {
		
		final SimParam.Dir par = param.dir(angIdx);
		
		Vec2d.Cplx [] separate  = Vec2d.createArrayCplx( par.nrComp(), w, h);
		BandSeparation.separateBands( inFFT[angIdx] , separate , 
		    par.getPhases(), par.nrBand(), par.getModulations());

		Vec2d.Cplx tmp  = Vec2d.createCplx( param, 2 );
		SimUtils.placeFreq( separate[0],  tmp);
		lowFreqResult.add( tmp );
	    }	
	    
	    // now, output the widefield
	    if (visualFeedback>0)
		pwSt2.addImage( SimUtils.pwSpec(lowFreqResult), "Widefield" );
	    spSt2.addImage( SimUtils.spatial(lowFreqResult, imgClipScale), "Widefield" );
	
	    // otf-multiply and wiener-filter the wide-field
	    otfPr.otfToVector( lowFreqResult, 0, 0, 0, false, false ); 

	    Vec2d.Real lfDenom = wFilter.getWidefieldDenominator( wienParam );
	    lowFreqResult.times( lfDenom );
	   
	    // mask out freq. that could not have passed 
	    // (does not really change the image)
	    otfPr.maskOtf( lowFreqResult, 0,0 );
	    
	    // could apodize the result, but that would just re-apply 
	    // the OTF (more or less)
	    // Vec2d.Cplx apoLowFreq = Vec2d.createCplx(2*w,2*h);
	    // otfPr.writeApoVector( apoLowFreq, 0.4, 1.2);
	    // lowFreqResult.times(apoLowFreq);
	    
	    if (visualFeedback>0)
		pwSt2.addImage( SimUtils.pwSpec( lowFreqResult), "filtered Widefield" );
	    spSt2.addImage( SimUtils.spatial( lowFreqResult, imgClipScale), "filtered Widefield" );

	}	

	// stop timers
	if (tRec!=null) tRec.stop();	

	Tool.tell("done.");

	// output parameters
	Tool.trace(" ---- Reconstruction ---- ");
	Tool.trace( "\n"+param.prettyPrint(true));
	
	pwSt.display();
	spSt.display();
	pwSt2.display();
	spSt2.display();

    }


}
