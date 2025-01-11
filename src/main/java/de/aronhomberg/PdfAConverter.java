package de.aronhomberg;

import com.spire.pdf.PdfCompressionLevel;
import com.spire.pdf.PdfDocument;
import com.spire.pdf.PdfNewDocument;
import com.spire.pdf.PdfPageBase;
import com.spire.pdf.conversion.PdfStandardsConverter;
import com.spire.pdf.graphics.PdfMargins;
import org.verapdf.core.VeraPDFException;
import org.verapdf.features.FeatureExtractorConfig;
import org.verapdf.features.FeatureFactory;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.metadata.fixer.FixerFactory;
import org.verapdf.metadata.fixer.MetadataFixerConfig;
import org.verapdf.pdfa.validation.validators.ValidatorConfig;
import org.verapdf.pdfa.validation.validators.ValidatorFactory;
import org.verapdf.processor.*;
import org.verapdf.processor.plugins.PluginsCollectionConfig;

import java.awt.geom.Dimension2D;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class PdfAConverter {

    public static String convertToPdfA(String pdfFile) {
        String outputPath = pdfFile.replace(".pdf", ".archive.pdf");
        PdfStandardsConverter sc = new PdfStandardsConverter(pdfFile);
        sc.toPdfA1A(outputPath);
        return outputPath;
    }

    public static void validatePDFA(String pdfAPath) {
        VeraGreenfieldFoundryProvider.initialise(); // Initialize the Foundry

        // Default configurations for validation, features, plugins, and metadata fixer
        ValidatorConfig validatorConfig = ValidatorFactory.defaultConfig();
        FeatureExtractorConfig featureConfig = FeatureFactory.defaultConfig();
        PluginsCollectionConfig pluginsConfig = PluginsCollectionConfig.defaultConfig();
        MetadataFixerConfig fixerConfig = FixerFactory.defaultConfig();

        // Specify tasks: Validate, Extract Features, Fix Metadata
        EnumSet<TaskType> tasks = EnumSet.of(TaskType.VALIDATE, TaskType.EXTRACT_FEATURES, TaskType.FIX_METADATA);

        // Processor configuration
        ProcessorConfig processorConfig = ProcessorFactory.fromValues(
            validatorConfig, featureConfig, pluginsConfig, fixerConfig, tasks
        );


        try (
                BatchProcessor processor = ProcessorFactory.fileBatchProcessor(processorConfig);
                OutputStream reportStream = System.out
        ) {
            // List of files to process
            List<File> files = new ArrayList<>();
            files.add(new File(pdfAPath));

            // Process files and write output to archive file
            processor.process(files, ProcessorFactory.getHandler(
                    FormatOption.MRR, true, reportStream, processorConfig.getValidatorConfig().isRecordPasses()
            ));
        } catch (VeraPDFException e) {
            System.err.println("Exception raised while processing batch");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("IOException occurred while writing report");
            e.printStackTrace();
        }
    }
}
