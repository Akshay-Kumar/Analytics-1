package edu.nyu.analytics.jobs;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

public class ArtistsPopularity {

	static HashSet<String> ignoreSongs = new HashSet<String>();
	static Map<String, String> mapOfSongIDVsTrackID = new HashMap<String, String>();
	static Map<String, HashSet<String>> artistSongsMap = new HashMap<String, HashSet<String>>();

	static Configuration config = HBaseConfiguration.create();
	static HTable table_songFrequency, table_artists;

	static Statement statement;

	static class SongFrequencyMapper extends Mapper<LongWritable, Text, Text, IntWritable> {

		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

			String line = value.toString();
			String songId = null;

			if (line.split(",").length >= 2)
				songId = line.split(",")[1];

			if (line.split(",").length >= 2 && !ignoreSongs.contains(songId)) {
				String trackId = "";

				if (mapOfSongIDVsTrackID.containsKey(songId)) {
					trackId = mapOfSongIDVsTrackID.get(songId);
				}

				String frequency = line.split(",")[2];
				context.write(new Text(trackId), new IntWritable(Integer.parseInt(frequency)));
			}
		}
	}

	static class SongFrequencyReducer extends Reducer<Text, IntWritable, Text, IntWritable> {

		public void reduce(Text key, Iterable<IntWritable> values, Context context) throws IOException, InterruptedException {

			int totalFreq = 0;
			for (IntWritable value : values) {
				totalFreq = totalFreq + value.get();
			}

			Put p = new Put(key.getBytes());
			p.add(Bytes.toBytes("listens"), Bytes.toBytes("someQualifier"), Bytes.toBytes(totalFreq));
			table_songFrequency.put(p);

			context.write(key, new IntWritable(totalFreq));
		}
	}

	static class ArtistsPopularityMapper extends Mapper<LongWritable, Text, Text, Text> {

		public void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {

			String line = value.toString();
			String trackId = "";
			String artistId = "";

			if (line.split(",").length >= 3) {
				artistId = line.split(",")[0];
			}

			int frequency = 0;

			ResultSet rs;
			try {
				rs = statement.executeQuery("SELECT track_id FROM songs WHERE artist_id='" + artistId + "'");

				while (rs.next()) {
					String tmp = rs.getString(1);

					Get g = new Get(Bytes.toBytes(tmp));
					Result r = table_songFrequency.get(g);
					int valueStr = 0;
					for (KeyValue kv : r.raw())
						valueStr = Bytes.toInt(kv.getValue());

					if (true) {
						if (!ignoreSongs.contains(tmp)) {
							try {
								int temp_int = valueStr;

								if (temp_int > frequency) {
									frequency = temp_int;
									trackId = tmp;
								}
							} catch (NumberFormatException e) {
							}
						}
					}
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}

			Put p = new Put(Bytes.toBytes(artistId));
			p.add(Bytes.toBytes("artist"), Bytes.toBytes("someQualifier"), Bytes.toBytes(artistId));
			p.add(Bytes.toBytes("song"), Bytes.toBytes("someQualifier"), Bytes.toBytes(trackId));
			p.add(Bytes.toBytes("frequency"), Bytes.toBytes("someQualifier"), Bytes.toBytes(frequency));

			table_artists.put(p);
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 4) {
			System.err.println("Usage: ArtistPopularity <input path> <temp path> <temp-output path> <output path>");
			System.exit(-1);
		}

		table_songFrequency = new HTable(config, "songFrequency");
		table_artists = new HTable(config, "artistsAndSongs");

		statement = dbConnect();

		BufferedReader reader = new BufferedReader(new FileReader("/Users/hiral/Documents/RealTimeBigData/Data/ignore.txt"));
		String line = "";
		while ((line = reader.readLine()) != null) {
			ignoreSongs.add(line);
		}
		reader.close();

		BufferedReader reader1 = new BufferedReader(new FileReader("/Users/hiral/Documents/RealTimeBigData/Data/allTrackEchonestId.txt"));
		String line1 = "";
		while ((line1 = reader1.readLine()) != null) {
			String[] arr = line1.split(",");
			mapOfSongIDVsTrackID.put(arr[1], arr[0]);
		}
		reader1.close();

		Configuration conf = new Configuration();
		Job job = new Job(conf, "first");
		job.setJarByClass(SongFrequency.class);

		FileInputFormat.addInputPath(job, new Path(args[0]));
		FileOutputFormat.setOutputPath(job, new Path(args[1]));

		job.setMapperClass(SongFrequencyMapper.class);
		job.setReducerClass(SongFrequencyReducer.class);

		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(IntWritable.class);
		job.waitForCompletion(true);

		System.out.println("First Job Completed.....Starting Second Job");
		System.out.println("Job completion was successful: " + job.isSuccessful());

		if (job.isSuccessful()) {
			System.out.println("Second job begins now..");

			Configuration conf2 = new Configuration();
			Job job2 = new Job(conf2, "second");
			job2.setJarByClass(ArtistsPopularity.class);

			FileInputFormat.addInputPath(job2, new Path(args[2]));
			FileOutputFormat.setOutputPath(job2, new Path(args[3]));

			job2.setMapperClass(ArtistsPopularityMapper.class);
			// job2.setReducerClass(ArtistsPopularityReducer.class);

			job2.setOutputKeyClass(Text.class);
			job2.setOutputValueClass(IntWritable.class);
			job2.waitForCompletion(true);

			System.out.println("Second job completed..");
			System.out.println("Job completion was successful: " + job2.isSuccessful());
		}

		table_songFrequency.close();
		table_artists.close();
	}

	public static Statement dbConnect() throws ClassNotFoundException {

		Class.forName("org.sqlite.JDBC");
		Connection connection = null;

		try {
			// create a database connection
			connection = DriverManager.getConnection("jdbc:sqlite:/Users/hiral/Documents/RealTimeBigData/Data/track_metadata.db");
			Statement statement = connection.createStatement();
			statement.setQueryTimeout(30); // set timeout to 30 sec.
			return statement;
		} catch (SQLException e) {
			// if the error message is "out of memory",
			// it probably means no database file is found
			System.err.println(e.getMessage());
		}
		return null;
	}
}
